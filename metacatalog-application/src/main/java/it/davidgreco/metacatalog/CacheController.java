package it.davidgreco.metacatalog;

import com.github.benmanes.caffeine.cache.Cache;
import it.davidgreco.metacatalog.entity.EntityType;
import it.davidgreco.metacatalog.entity.Trait;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Debug endpoint for inspecting the Spring cache contents.
 *
 * <p>Exposes the two cached repositories — {@code Traits} and {@code EntityTypes} — showing each
 * entry's key, value class, and (for entity types) the number of mixed-in traits.
 *
 * <p>Access at {@code GET /_admin/cache}.
 */
@Controller
@RequestMapping("/_admin")
class CacheController {

  private final CacheManager cacheManager;

  CacheController(CacheManager cacheManager) {
    this.cacheManager = cacheManager;
  }

  /**
   * Returns cache contents as JSON.
   *
   * @param name optional cache name filter. If omitted, all caches are returned.
   * @param limit max entries per cache (default 100). Use 0 for unlimited.
   */
  @GetMapping("/cache")
  @ResponseBody
  public Map<String, Object> cache(
      @RequestParam(required = false) String name, @RequestParam(defaultValue = "100") int limit) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (String cacheName : cacheManager.getCacheNames()) {
      if (name != null && !cacheName.equals(name)) {
        continue;
      }
      var springCache = cacheManager.getCache(cacheName);
      if (springCache == null) {
        result.put(cacheName, Map.of("error", "cache not found"));
        continue;
      }
      Cache<?, ?> underlying = (Cache<?, ?>) springCache.getNativeCache();
      if (underlying == null) {
        result.put(cacheName, Map.of("error", "no native cache"));
        continue;
      }
      Map<String, Object> cacheInfo = new LinkedHashMap<>();
      cacheInfo.put("size", underlying.estimatedSize());
      cacheInfo.put(
          "entries",
          underlying.asMap().entrySet().stream()
              .limit(limit == 0 ? Integer.MAX_VALUE : limit)
              .collect(
                  Collectors.toMap(e -> String.valueOf(e.getKey()), e -> summarize(e.getValue()))));
      result.put(cacheName, cacheInfo);
    }
    return result;
  }

  /**
   * Summarizes a cached value for display.
   *
   * <p>For {@link EntityType} and {@link Trait} entities, includes the name and relevant metadata.
   * For other types, returns the class name.
   */
  private static Map<String, Object> summarize(Object value) {
    if (value == null) {
      return Map.of("type", "null");
    }
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("type", value.getClass().getName());
    if (value instanceof EntityType entityType) {
      summary.put("name", entityType.getName());
      summary.put("traits", entityType.getTraits().size());
      if (entityType.getFather() != null) {
        summary.put("father", entityType.getFather().getName());
      }
    } else if (value instanceof Trait trait) {
      summary.put("name", trait.getName());
      if (trait.getFather() != null) {
        summary.put("father", trait.getFather().getName());
      }
    } else {
      summary.put("value", String.valueOf(value));
    }
    return summary;
  }
}
