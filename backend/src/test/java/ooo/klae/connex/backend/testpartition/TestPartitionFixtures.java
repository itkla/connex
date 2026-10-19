package ooo.klae.connex.backend.testpartition;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Compiled verifier probes excluded from every Gradle test suite. */
abstract class TestPartitionFixtures {
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
    @Test
    @interface ComposedTest {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @ComposedTest
    @interface TwiceComposedTest {
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @ParameterizedTest
    @interface ComposedParameterizedTest {
    }

    static class ComposedOnly {
        @TwiceComposedTest
        void composed() {
        }
    }

    static class ParameterizedOnly {
        @ComposedParameterizedTest
        @ValueSource(booleans = true)
        void parameterized(boolean value) {
        }
    }

    static class StaticOnly {
        @Test
        void independent() {
        }
    }

    abstract static class InheritedBase {
        @Test
        void inherited() {
        }
    }

    static class InheritedOnly extends InheritedBase {
    }

    static class NestedOwner {
        @Nested
        class Inner {
            @Test
            void nested() {
            }
        }
    }

    static class ExcludedOwner {
        @Test
        void excluded() {
        }

        static class Helper {
        }
    }
}
