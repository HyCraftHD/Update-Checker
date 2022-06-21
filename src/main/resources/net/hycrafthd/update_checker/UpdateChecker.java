package net.hycrafthd.update_checker;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import net.minecraftforge.fml.VersionChecker;
import net.minecraftforge.fml.VersionChecker.CheckResult;
import net.minecraftforge.fml.VersionChecker.ModInfo;

public class UpdateChecker {
	
	private static final Map<Mod, ModInfo> modMap = new LinkedHashMap<>();
	
	public static void check(String minecraftVersion, List<Mod> mods, Function<String, Version<?>> versionMapper) {
		for (final Mod mod : mods) {
			try {
				modMap.put(mod, new ModInfo(mod.modid, mod.currentVersion, new URL(mod.updateUrl)));
			} catch (final MalformedURLException ex) {
				throw new RuntimeException("Update url is malformed", ex);
			}
		}
		
		VersionChecker.startVersionCheck(modMap.values(), minecraftVersion, versionMapper);
	}
	
	public static Result result(Mod mod) {
		final CheckResult result = VersionChecker.getResult(modMap.get(mod));
		return new Result(result.status(), result.target(), result.changes(), result.url());
	}
	
	public static record Mod(String modid, String currentVersion, String updateUrl) {
	}
	
	public static record Result(VersionChecker.Status status, Version<?> target, Map<Version<?>, String> changes, String url) {
	}
	
}
