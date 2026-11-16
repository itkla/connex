package ooo.klae.connex.backend.dto;

import java.util.Objects;
import java.util.function.Consumer;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.annotation.JsonSerialize;

import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.NoteDto;

/** A synchronous note-array source whose lifetime cannot exceed its tenant snapshot callback. */
@JsonSerialize(using = DataSubjectDisclosureNotes.Serializer.class)
public final class DataSubjectDisclosureNotes implements AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private PageSource source;

    public DataSubjectDisclosureNotes(Consumer<Consumer<NoteDto>> source) {
        this(pageSource(source));
    }

    private static PageSource pageSource(Consumer<Consumer<NoteDto>> source) {
        Objects.requireNonNull(source);
        return (consumer, beforePage) -> {
            beforePage.run();
            source.accept(consumer);
        };
    }

    public DataSubjectDisclosureNotes(PageSource source) {
        this.source = Objects.requireNonNull(source);
    }

    @Override
    public void close() {
        source = null;
    }

    /** Observes the exact UTF-8 array boundaries after the generator has flushed its prefix. */
    public interface Observer {
        void beforePage();
        void beginNotes();
        void noteWritten();
        void endNotes();
    }

    /** Invokes the boundary before reading and before writing each bounded page. */
    @FunctionalInterface
    public interface PageSource {
        void accept(Consumer<NoteDto> consumer, Runnable beforePage);
    }

    /** Serializes the ordinary notes array without retaining notes or serialized payloads. */
    public static final class Serializer extends ValueSerializer<DataSubjectDisclosureNotes> {
        @Override
        public void serialize(DataSubjectDisclosureNotes notes, JsonGenerator generator,
                SerializationContext context) {
            if (Thread.currentThread() != notes.owner || notes.source == null) {
                throw new IllegalStateException("Disclosure notes cannot outlive their synchronous snapshot");
            }
            Observer observer = (Observer) context.getAttribute(Observer.class);
            generator.writeStartArray();
            generator.flush();
            if (observer != null) {
                observer.beginNotes();
            }
            notes.source.accept(note -> {
                context.writeValue(generator, note);
                if (observer != null) {
                    observer.noteWritten();
                }
            }, () -> {
                if (observer != null) {
                    observer.beforePage();
                }
            });
            generator.writeEndArray();
            generator.flush();
            if (observer != null) {
                observer.endNotes();
            }
        }
    }
}
