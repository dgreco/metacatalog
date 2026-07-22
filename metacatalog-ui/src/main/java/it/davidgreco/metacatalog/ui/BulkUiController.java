package it.davidgreco.metacatalog.ui;

import it.davidgreco.metacatalog.service.BulkLoaderService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Bulk YAML upload form and handler: accepts a YAML file or pasted text and feeds it to {@link
 * BulkLoaderService} for model or aggregate creation.
 */
@Controller
@RequestMapping("/ui")
public class BulkUiController {

  private final BulkLoaderService bulkLoaderService;

  public BulkUiController(BulkLoaderService bulkLoaderService) {
    this.bulkLoaderService = bulkLoaderService;
  }

  /** Renders the bulk YAML upload form. */
  @GetMapping("/bulk")
  public String bulkForm() {
    return "bulk-form";
  }

  /**
   * Handles a bulk upload. Accepts either an uploaded YAML file or pasted YAML text and, depending
   * on {@code kind}, feeds it to {@link BulkLoaderService#bulkModelCreation} (traits, entity types,
   * relationships and mappings) or {@link BulkLoaderService#bulkAggregateCreation} (aggregates /
   * entities).
   */
  @PostMapping("/bulk")
  public String bulkUpload(
      @RequestParam(value = "file", required = false) MultipartFile file,
      @RequestParam(value = "yamlText", required = false) String yamlText,
      @RequestParam(value = "kind", required = false, defaultValue = "model") String kind,
      Model model,
      RedirectAttributes redirectAttributes) {
    try (InputStream in = resolveBulkInput(file, yamlText)) {
      if (in == null) {
        model.addAttribute("error", "Provide a YAML file or paste YAML text.");
        return "bulk-form";
      }
      if ("aggregates".equals(kind)) {
        var ids = bulkLoaderService.bulkAggregateCreation(in);
        redirectAttributes.addFlashAttribute("message", ids.size() + " aggregate(s) created.");
      } else {
        bulkLoaderService.bulkModelCreation(in);
        redirectAttributes.addFlashAttribute("message", "Bulk model uploaded successfully.");
      }
      return "redirect:/ui";
    } catch (RuntimeException | IOException e) {
      model.addAttribute("error", e.getMessage());
      return "bulk-form";
    }
  }

  /**
   * Returns the YAML source: the uploaded file if present, otherwise the pasted text, else null.
   */
  private static InputStream resolveBulkInput(MultipartFile file, String yamlText)
      throws IOException {
    if (file != null && !file.isEmpty()) {
      return file.getInputStream();
    }
    if (yamlText != null && !yamlText.isBlank()) {
      return new ByteArrayInputStream(yamlText.getBytes(StandardCharsets.UTF_8));
    }
    return null;
  }
}
