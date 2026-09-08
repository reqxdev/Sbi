package moe.example.skyblockrecipeviewer.repo;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.Logger;

/**
 * Downloads the public NotEnoughUpdates-REPO item repository from GitHub and extracts it
 * to disk, mirroring the exact layout/versioning convention the original NotEnoughUpdates
 * mod uses at config/notenoughupdates/ (a "repo" folder plus a currentCommit.json sha
 * marker) - so this mod reads and writes the *same* shared copy other SkyBlock mods use,
 * rather than keeping a separate copy of the same data.
 *
 * This is plain data (item/recipe JSON), not the mod's own release artifact - there is no
 * self-update-checker here, and nothing about installed mods is ever sent anywhere.
 */
public final class RepoDownloader {

	private static final String OWNER = "NotEnoughUpdates";
	private static final String REPO = "NotEnoughUpdates-REPO";
	private static final String BRANCH = "master";

	private final Path repoDir;
	private final Path commitFile;
	private final Logger logger;
	private final HttpClient httpClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(15))
		// GitHub's codeload/archive endpoints redirect (302) fairly routinely - e.g. between
		// CDN endpoints - and HttpClient's default redirect policy is NEVER, meaning every
		// archive download was failing outright with "GitHub archive download returned HTTP
		// 302" instead of ever reaching the actual zip. NORMAL follows redirects except
		// HTTPS->HTTP downgrades, which is the right default for a GET against a fixed,
		// known-HTTPS host like this.
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();

	/**
	 * @param neuConfigDir the shared config/notenoughupdates directory - repo goes in
	 *                     neuConfigDir/repo, version marker in neuConfigDir/currentCommit.json,
	 *                     matching the original NotEnoughUpdates mod's own layout exactly.
	 */
	public RepoDownloader(Path neuConfigDir, Logger logger) {
		this.repoDir = neuConfigDir.resolve("repo");
		this.commitFile = neuConfigDir.resolve("currentCommit.json");
		this.logger = logger;
	}

	public Path getRepoDir() {
		return repoDir;
	}

	private String loadSavedSha() {
		try {
			if (Files.exists(repoDir) && Files.exists(commitFile)) {
				JsonObject json = JsonParser.parseString(Files.readString(commitFile, StandardCharsets.UTF_8))
					.getAsJsonObject();
				return json.has("sha") ? json.get("sha").getAsString() : null;
			}
		} catch (Exception ignored) {
		}
		return null;
	}

	private void saveSha(String sha) throws IOException {
		JsonObject json = new JsonObject();
		json.addProperty("sha", sha);
		json.addProperty("time", System.currentTimeMillis());
		Files.createDirectories(commitFile.getParent());
		Files.writeString(commitFile, json.toString(), StandardCharsets.UTF_8);
	}

	private String fetchLatestSha() throws IOException, InterruptedException {
		String url = "https://api.github.com/repos/" + OWNER + "/" + REPO + "/commits/" + BRANCH;
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.header("Accept", "application/vnd.github+json")
			.GET()
			.build();
		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IOException("GitHub API returned HTTP " + response.statusCode());
		}
		JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
		return json.get("sha").getAsString();
	}

	/**
	 * Compares the locally recorded sha (from currentCommit.json) against GitHub's latest
	 * commit on the branch, without downloading anything. Safe to call from a background
	 * thread; intended to run once per Hypixel connection rather than on every launch.
	 *
	 * @return the latest sha if it differs from what's on disk (or nothing is on disk yet),
	 *         or empty if already up to date or the check failed (e.g. offline)
	 */
	public java.util.Optional<String> checkForUpdate() {
		String latestSha;
		try {
			latestSha = fetchLatestSha();
		} catch (Exception e) {
			logger.warn("Could not reach GitHub to check for SkyBlock item repo updates ({}).", e.toString());
			return java.util.Optional.empty();
		}
		String currentSha = loadSavedSha();
		if (latestSha.equals(currentSha)) {
			logger.debug("SkyBlock item repo already up to date ({})", currentSha);
			return java.util.Optional.empty();
		}
		return java.util.Optional.of(latestSha);
	}

	/**
	 * Downloads and extracts the given sha's archive over the shared repo folder, then
	 * records it in currentCommit.json. Safe to call from a background thread.
	 *
	 * @return true on success
	 */
	public boolean downloadAndApply(String sha) {
		try {
			String downloadUrl = "https://github.com/" + OWNER + "/" + REPO + "/archive/" + sha + ".zip";
			logger.info("Downloading SkyBlock item repo (-> {})", sha);
			Path zip = downloadArchive(downloadUrl);
			deleteRecursively(repoDir);
			extract(zip, repoDir);
			Files.deleteIfExists(zip);
			saveSha(sha);
			logger.info("SkyBlock item repo updated to {}.", sha);
			return true;
		} catch (Exception e) {
			logger.error("Failed to download/extract SkyBlock item repo", e);
			return false;
		}
	}

	private Path downloadArchive(String url) throws IOException, InterruptedException {
		Path tempZip = Files.createTempFile("skyblock-repo", ".zip");
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
		HttpResponse<Path> response = httpClient.send(request,
			HttpResponse.BodyHandlers.ofFile(tempZip));
		if (response.statusCode() != 200) {
			throw new IOException("GitHub archive download returned HTTP " + response.statusCode());
		}
		return response.body();
	}

	/**
	 * Extracts the zip, stripping the single top-level "reponame-sha/" folder GitHub wraps
	 * archives in, with a zip-slip path traversal guard.
	 */
	private void extract(Path zipFile, Path targetDir) throws IOException {
		Files.createDirectories(targetDir);
		Path normalizedTarget = targetDir.normalize();
		try (InputStream fis = Files.newInputStream(zipFile);
			 ZipInputStream zis = new ZipInputStream(fis)) {
			ZipEntry entry;
			while ((entry = zis.getNextEntry()) != null) {
				if (entry.isDirectory()) continue;
				String name = entry.getName();
				int slash = name.indexOf('/');
				String relative = slash >= 0 ? name.substring(slash + 1) : "";
				if (relative.isEmpty()) continue;

				Path outPath = normalizedTarget.resolve(relative).normalize();
				if (!outPath.startsWith(normalizedTarget)) {
					throw new IOException("Zip entry escapes target directory (zip-slip): " + name);
				}
				Files.createDirectories(outPath.getParent());
				Files.copy(zis, outPath, StandardCopyOption.REPLACE_EXISTING);
			}
		}
	}

	private void deleteRecursively(Path dir) throws IOException {
		if (!Files.exists(dir)) return;
		try (var walk = Files.walk(dir)) {
			walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
				}
			});
		}
	}
}
