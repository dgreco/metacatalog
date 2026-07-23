package it.davidgreco.metacatalog.ui;

/**
 * Form backing bean for creating an aggregate instance from the UI.
 *
 * <p>The {@code yaml} field holds an aggregate YAML document parsed by the bulk loader.
 */
public class AggregateForm {

  private String yaml;

  public String getYaml() {
    return yaml;
  }

  public void setYaml(String yaml) {
    this.yaml = yaml;
  }
}
