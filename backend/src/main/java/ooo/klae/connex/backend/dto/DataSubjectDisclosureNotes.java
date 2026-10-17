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
    private Consumer<Consumer<NoteDto>> source;

    public DataSubjectDisclosureNotes(Consumer<Consumer<NoteDto>> source) {
        this.source = Objects.requireNonNull(source);
    }

    @Override
    public void close() {
        source = null;
    }

    /** Observes the exact UTF-8 array boundaries after the generator has flushed its prefix. */
    public interface Observer {
        void beginNotes();
        void noteWritten();
        void endNotes();
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
            });
            generator.writeEndArray();
            generator.flush();
            if (observer != null) {
                observer.endNotes();
            }
        }
    }
}
