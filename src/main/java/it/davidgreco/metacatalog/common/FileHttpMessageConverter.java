package it.davidgreco.metacatalog.common;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractGenericHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;

public class FileHttpMessageConverter extends AbstractGenericHttpMessageConverter<File> {

    public FileHttpMessageConverter() {
        super(MediaType.APPLICATION_OCTET_STREAM, MediaType.ALL);
    }

    @Override
    public boolean supports(Class<?> clazz) {
        return File.class == clazz;
    }

    @Override
    protected void writeInternal(File file, Type type, HttpOutputMessage outputMessage)
            throws IOException, HttpMessageNotWritableException {
        Path filePath = file.toPath();
        Files.copy(filePath, outputMessage.getBody());
    }

    @Override
    protected File readInternal(Class<? extends File> clazz, HttpInputMessage inputMessage)
            throws IOException, HttpMessageNotReadableException {
        throw new UnsupportedOperationException();
    }

    @Override
    public File read(Type type, Class<?> contextClass, HttpInputMessage inputMessage)
            throws IOException, HttpMessageNotReadableException {
        throw new UnsupportedOperationException();
    }
}
