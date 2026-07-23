package it.davidgreco.metacatalog.ui;

import it.davidgreco.metacatalog.openapi.controller.MetacatalogApiDelegate;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Bulk YAML upload form and handler: accepts a YAML file or pasted text and feeds it to the REST
 * API bulk-creation endpoint.
 */
@Controller
@RequestMapping("/ui")
public class BulkUiController {

  private final MetacatalogApiDelegate api;

  public BulkUiController(MetacatalogApiDelegate api) {
    this.api = api;
  }

  @GetMapping("/bulk")
  public String bulkForm() {
    return "bulk-form";
  }

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
      Resource resource = new InputStreamResource(in);
      if ("aggregates".equals(kind)) {
        api.createAggregateAsYaml(resource);
        redirectAttributes.addFlashAttribute("message", "Aggregates created.");
      } else {
        api.bulkCreation(resource);
        redirectAttributes.addFlashAttribute("message", "Bulk model uploaded successfully.");
      }
      return "redirect:/ui";
    } catch (RuntimeException | IOException e) {
      model.addAttribute("error", e.getMessage());
      return "bulk-form";
    }
  }

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
