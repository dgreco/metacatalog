package it.davidgreco.metacatalog.openapi.common;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractGenericHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;

/**
 * HTTP message converter for File objects.
 *
 * <p>This converter handles reading and writing File objects in HTTP messages, supporting
 * APPLICATION_OCTET_STREAM and ALL media types. It is used for file upload and download operations
 * in the REST API.
 */
public class FileHttpMessageConverter extends AbstractGenericHttpMessageConverter<File> {

  /** Creates a new converter supporting octet-stream and all media types. */
  public FileHttpMessageConverter() {
    super(MediaType.APPLICATION_OCTET_STREAM, MediaType.ALL);
  }

  /**
   * Checks if this converter supports the given class.
   *
   * @param clazz the class to check
   * @return true if the class is File, false otherwise
   */
  @Override
  public boolean supports(Class<?> clazz) {
    return File.class == clazz;
  }

  /**
   * Writes a File to the HTTP output message.
   *
   * @param file the file to write
   * @param type the type of the file
   * @param outputMessage the HTTP output message
   * @throws IOException if an I/O error occurs
   * @throws HttpMessageNotWritableException if the message cannot be written
   */
  @Override
  protected void writeInternal(File file, Type type, HttpOutputMessage outputMessage)
      throws IOException, HttpMessageNotWritableException {
    Path filePath = file.toPath();
    Files.copy(filePath, outputMessage.getBody());
  }

  /**
   * Reads a File from the HTTP input message (not supported for class-based reading).
   *
   * @param clazz the expected file class
   * @param inputMessage the HTTP input message
   * @return never returns
   * @throws HttpMessageNotReadableException always, as this operation is not supported
   */
  @Override
  protected File readInternal(Class<? extends File> clazz, HttpInputMessage inputMessage)
      throws HttpMessageNotReadableException {
    throw new UnsupportedOperationException();
  }

  /**
   * Reads a File from the HTTP input message by creating a temporary file.
   *
   * @param type the expected type
   * @param contextClass the context class
   * @param inputMessage the HTTP input message
   * @return the created temporary file containing the input message body
   * @throws IOException if an I/O error occurs
   * @throws HttpMessageNotReadableException if the message cannot be read
   */
  @Override
  public File read(Type type, Class<?> contextClass, HttpInputMessage inputMessage)
      throws IOException, HttpMessageNotReadableException {
    var file = Files.createTempFile("temp", ".tmp");
    var result = file.toFile();
    // Ensure the temporary file does not leak: without this every upload leaves a stale temp file
    // behind for the lifetime of the JVM.
    result.deleteOnExit();
    try (OutputStream fos = Files.newOutputStream(file)) {
      inputMessage.getBody().transferTo(fos);
    }
    return result;
  }
}
