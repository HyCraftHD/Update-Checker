/*
 * Adapted from
 * https://github.com/MinecraftForge/MinecraftForge/blob/05240fecf7fd4391e7ff9ad4e6a0007d2a1df926/fmlcore/src/main/java/
 * net/minecraftforge/fml/VersionChecker.java under LGPL-2.1 license. Changes by hycrafthd made it independent from fml.
 * Copyright (c) Forge Development LLC and contributors SPDX-License-Identifier: LGPL-2.1-only
 */

package net.minecraftforge.fml;

import static net.minecraftforge.fml.VersionChecker.Status.AHEAD;
import static net.minecraftforge.fml.VersionChecker.Status.BETA;
import static net.minecraftforge.fml.VersionChecker.Status.BETA_OUTDATED;
import static net.minecraftforge.fml.VersionChecker.Status.FAILED;
import static net.minecraftforge.fml.VersionChecker.Status.OUTDATED;
import static net.minecraftforge.fml.VersionChecker.Status.PENDING;
import static net.minecraftforge.fml.VersionChecker.Status.UP_TO_DATE;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

import net.hycrafthd.update_checker.Version;

public class VersionChecker {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(VersionChecker.class);
	private static final int MAX_HTTP_REDIRECTS = Integer.getInteger("http.maxRedirects", 20);
	private static final int HTTP_TIMEOUT_SECS = Integer.getInteger("http.timeoutSecs", 15);
	
	public enum Status {
		
		PENDING(),
		FAILED(),
		UP_TO_DATE(),
		OUTDATED(3, true),
		AHEAD(),
		BETA(),
		BETA_OUTDATED(6, true);
		
		final int sheetOffset;
		final boolean draw, animated;
		
		Status() {
			this(0, false, false);
		}
		
		Status(int sheetOffset) {
			this(sheetOffset, true, false);
		}
		
		Status(int sheetOffset, boolean animated) {
			this(sheetOffset, true, animated);
		}
		
		Status(int sheetOffset, boolean draw, boolean animated) {
			this.sheetOffset = sheetOffset;
			this.draw = draw;
			this.animated = animated;
		}
		
		public int getSheetOffset() {
			return sheetOffset;
		}
		
		public boolean shouldDraw() {
			return draw;
		}
		
		public boolean isAnimated() {
			return animated;
		}
		
	}
	
	public record CheckResult(VersionChecker.Status status, Version<?> target, Map<Version<?>, String> changes, String url) {
	}
	
	public record ModInfo(String modid, String currentVersion, URL url) {
	}
	
	public static void startVersionCheck(Collection<ModInfo> modInfo, String minecraftVersion, Function<String, Version<?>> versionMapper) {
		new Thread("Version Check") {
			
			private HttpClient client;
			
			@Override
			public void run() {
				client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECS)).build();
				modInfo.forEach(this::process);
			}
			
			/**
			 * Returns the response body as a String for the given URL while following redirects
			 */
			private String openUrlString(URL url) throws IOException, URISyntaxException, InterruptedException {
				URL currentUrl = url;
				for (int redirects = 0; redirects < MAX_HTTP_REDIRECTS; redirects++) {
					var request = HttpRequest.newBuilder().uri(currentUrl.toURI()).timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECS)).setHeader("Accept-Encoding", "gzip").GET().build();
					
					final HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
					
					int responseCode = response.statusCode();
					if (responseCode >= 300 && responseCode <= 399) {
						String newLocation = response.headers().firstValue("Location").orElseThrow(() -> new IOException("Got a 3xx response code but Location header was null while trying to fetch " + url));
						currentUrl = new URL(currentUrl, newLocation);
						continue;
					}
					
					final boolean isGzipEncoded = response.headers().firstValue("Content-Encoding").orElse("").equals("gzip");
					
					final String bodyStr;
					try (InputStream inStream = isGzipEncoded ? new GZIPInputStream(response.body()) : response.body()) {
						try (var bufferedReader = new BufferedReader(new InputStreamReader(inStream))) {
							bodyStr = bufferedReader.lines().collect(Collectors.joining("\n"));
						}
					}
					return bodyStr;
				}
				throw new IOException("Too many redirects while trying to fetch " + url);
			}
			
			private void process(ModInfo mod) {
				Status status = PENDING;
				Version<?> target = null;
				Map<Version<?>, String> changes = null;
				String display_url = null;
				try {
					if (mod.url == null)
						return;
					URL url = mod.url;
					LOGGER.info("[{}] Starting version check at {}", mod.modid, url.toString());
					
					String data = openUrlString(url);
					
					LOGGER.debug("[{}] Received version check data:\n{}", mod.modid, data);
					
					@SuppressWarnings("unchecked")
					Map<String, Object> json = new Gson().fromJson(data, Map.class);
					@SuppressWarnings("unchecked")
					Map<String, String> promos = (Map<String, String>) json.get("promos");
					display_url = (String) json.get("homepage");
					
					String rec = promos.get(minecraftVersion + "-recommended");
					String lat = promos.get(minecraftVersion + "-latest");
					Version<?> current = versionMapper.apply(mod.currentVersion.toString());
					
					if (rec != null) {
						Version<?> recommended = versionMapper.apply(rec);
						int diff = recommended.compareTo(current);
						
						if (diff == 0)
							status = UP_TO_DATE;
						else if (diff < 0) {
							status = AHEAD;
							if (lat != null) {
								Version<?> latest = versionMapper.apply(lat);
								if (current.compareTo(latest) < 0) {
									status = OUTDATED;
									target = latest;
								}
							}
						} else {
							status = OUTDATED;
							target = recommended;
						}
					} else if (lat != null) {
						Version<?> latest = versionMapper.apply(lat);
						if (current.compareTo(latest) < 0)
							status = BETA_OUTDATED;
						else
							status = BETA;
						target = latest;
					} else
						status = BETA;
					
					LOGGER.info("[{}] Found status: {} Current: {} Target: {}", mod.modid, status, current, target);
					
					changes = new LinkedHashMap<>();
					@SuppressWarnings("unchecked")
					Map<String, String> tmp = (Map<String, String>) json.get(minecraftVersion);
					if (tmp != null) {
						List<Version<?>> ordered = new ArrayList<>();
						for (String key : tmp.keySet()) {
							Version<?> ver = versionMapper.apply(key);
							if (ver.compareTo(current) > 0 && (target == null || ver.compareTo(target) < 1)) {
								ordered.add(ver);
							}
						}
						Collections.sort(ordered);
						
						for (Version<?> ver : ordered) {
							changes.put(ver, tmp.get(ver.toString()));
						}
					}
				} catch (Exception e) {
					LOGGER.warn("Failed to process update information", e);
					status = FAILED;
				}
				results.put(mod, new CheckResult(status, target, changes, display_url));
			}
		}.start();
	}
	
	private static Map<ModInfo, CheckResult> results = new ConcurrentHashMap<>();
	private static final CheckResult PENDING_CHECK = new CheckResult(PENDING, null, null, null);
	
	public static CheckResult getResult(ModInfo mod) {
		return results.getOrDefault(mod, PENDING_CHECK);
	}
	
}
