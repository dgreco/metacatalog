package it.davidgreco.metacatalog.iceberg;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.NotFoundException;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.io.PositionOutputStream;
import org.apache.iceberg.io.SeekableInputStream;

/**
 * A {@link FileIO} for {@code file://} (and plain-path) locations built on {@code java.nio},
 * avoiding the Hadoop dependency that {@code HadoopFileIO} — iceberg-core's only built-in answer
 * for local filesystems — would drag in. Intended for development, demos and tests; production
 * deployments point {@code application.config.iceberg.io-impl} at {@code S3FileIO} or another cloud
 * FileIO.
 *
 * <p>Instantiated reflectively by {@code CatalogUtil.loadFileIO}, hence the public no-arg
 * constructor.
 */
public class LocalFileIO implements FileIO {

  public LocalFileIO() {}

  @Override
  public InputFile newInputFile(String location) {
    return new LocalInputFile(location);
  }

  @Override
  public OutputFile newOutputFile(String location) {
    return new LocalOutputFile(location);
  }

  @Override
  public void deleteFile(String location) {
    try {
      Files.deleteIfExists(toPath(location));
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to delete " + location, e);
    }
  }

  private static Path toPath(String location) {
    if (location.startsWith("file:")) {
      // URI.create chokes on unescaped characters; strip the scheme manually instead so that
      // locations like file:///a/b and file:/a/b both resolve.
      var withoutScheme = URI.create(location).getPath();
      if (withoutScheme == null) {
        withoutScheme = location.substring("file:".length());
      }
      return Path.of(withoutScheme);
    }
    return Path.of(location);
  }

  private record LocalInputFile(String location) implements InputFile {

    @Override
    public long getLength() {
      try {
        return Files.size(toPath(location));
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to get length of " + location, e);
      }
    }

    @Override
    public SeekableInputStream newStream() {
      try {
        return new SeekableFileInputStream(Files.newByteChannel(toPath(location)));
      } catch (NoSuchFileException e) {
        throw new NotFoundException(e, "File does not exist: %s", location);
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to open " + location, e);
      }
    }

    @Override
    public boolean exists() {
      return Files.exists(toPath(location));
    }
  }

  private record LocalOutputFile(String location) implements OutputFile {

    @Override
    public PositionOutputStream create() {
      return open(StandardOpenOption.CREATE_NEW);
    }

    @Override
    public PositionOutputStream createOrOverwrite() {
      return open(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private PositionOutputStream open(StandardOpenOption... options) {
      var path = toPath(location);
      try {
        if (path.getParent() != null) {
          Files.createDirectories(path.getParent());
        }
        var openOptions = new StandardOpenOption[options.length + 1];
        System.arraycopy(options, 0, openOptions, 0, options.length);
        openOptions[options.length] = StandardOpenOption.WRITE;
        return new PositionFileOutputStream(Files.newOutputStream(path, openOptions));
      } catch (java.nio.file.FileAlreadyExistsException e) {
        throw new AlreadyExistsException(e, "File already exists: %s", location);
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to create " + location, e);
      }
    }

    @Override
    public InputFile toInputFile() {
      return new LocalInputFile(location);
    }
  }

  private static final class SeekableFileInputStream extends SeekableInputStream {

    private final SeekableByteChannel channel;
    private final InputStream stream;

    private SeekableFileInputStream(SeekableByteChannel channel) {
      this.channel = channel;
      this.stream = Channels.newInputStream(channel);
    }

    @Override
    public long getPos() throws IOException {
      return channel.position();
    }

    @Override
    public void seek(long newPos) throws IOException {
      channel.position(newPos);
    }

    @Override
    public int read() throws IOException {
      return stream.read();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      return stream.read(b, off, len);
    }

    @Override
    public void close() throws IOException {
      stream.close();
    }
  }

  private static final class PositionFileOutputStream extends PositionOutputStream {

    private final OutputStream delegate;
    private long position;

    private PositionFileOutputStream(OutputStream delegate) {
      this.delegate = delegate;
    }

    @Override
    public long getPos() {
      return position;
    }

    @Override
    public void write(int b) throws IOException {
      delegate.write(b);
      position++;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      delegate.write(b, off, len);
      position += len;
    }

    @Override
    public void flush() throws IOException {
      delegate.flush();
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }
  }
}
