package eu.toolchain.serializer.processor;

import static com.google.common.truth.Truth.assert_;
import static com.google.testing.compile.JavaSourceSubjectFactory.javaSource;
import static com.google.testing.compile.JavaSourcesSubjectFactory.javaSources;

import java.util.ArrayList;
import java.util.List;

import javax.tools.JavaFileObject;

import org.junit.Test;

import com.google.testing.compile.JavaFileObjects;

public class AutoSerializerProcessorTest {
    @Test
    public void testEmpty() {
        verifySerializer("Empty");
    }

    @Test
    public void testInterface() {
        verifySerializer("Interface");
    }

    @Test
    public void testFields() {
        verifySerializer("Fields");
    }

    @Test
    public void testGetter() {
        verifySerializer("Getter");
    }

    @Test
    public void testCustomAccessor() {
        verifySerializer("CustomAccessor");
    }

    @Test
    public void testImplicitConstructor() {
        verifySerializer("ImplicitConstructor");
    }

    @Test(expected = RuntimeException.class)
    public void testMissingEmptyConstructor() {
        verifyFailingSerializer("MissingEmptyConstructor");
    }

    @Test
    public void testSerialization() {
        final List<JavaFileObject> files = new ArrayList<>();

        files.add(resourcePathFor("Empty"));
        files.add(resourcePathFor("Interface"));
        files.add(resourcePathFor("ImplA"));
        files.add(resourcePathFor("ImplB"));

        assert_().about(javaSources()).that(files).processedWith(new AutoSerializerProcessor()).compilesWithoutError()
                .and().generatesSources(resourcePathFor("Serialization"));
    }

    static void verifySerializer(String name) {
        final JavaFileObject source = resourcePathFor(name);
        final JavaFileObject serializer = resourcePathFor(String.format(AutoSerializerProcessor.SERIALIZER_NAME_FORMAT,
                name));

        assert_().about(javaSource()).that(source).processedWith(new AutoSerializerProcessor()).compilesWithoutError()
                .and().generatesSources(serializer);
    }

    static void verifyFailingSerializer(String name) {
        final JavaFileObject source = resourcePathFor(name);
        assert_().about(javaSource()).that(source).processedWith(new AutoSerializerProcessor()).failsToCompile();
    }

    static JavaFileObject resourcePathFor(String name) {
        final String dirName = AutoSerializerProcessorTest.class.getPackage().getName().replace('.', '/');
        return JavaFileObjects.forResource(String.format("%s/%s.java", dirName, name));
    }
}