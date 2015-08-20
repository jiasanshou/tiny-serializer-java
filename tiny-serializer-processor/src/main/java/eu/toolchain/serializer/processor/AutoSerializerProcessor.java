package eu.toolchain.serializer.processor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

import com.google.auto.service.AutoService;
import com.google.common.base.CaseFormat;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.MethodSpec.Builder;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;

import eu.toolchain.serializer.AutoSerialize;
import eu.toolchain.serializer.SerialReader;
import eu.toolchain.serializer.SerialWriter;
import eu.toolchain.serializer.Serializer;
import eu.toolchain.serializer.SerializerFramework;
import eu.toolchain.serializer.SerializerFramework.TypeMapping;

@AutoService(Processor.class)
public class AutoSerializerProcessor extends AbstractProcessor {
    public static final String FRAMEWORK_NAME = "framework";

    static final Joiner parameterJoiner = Joiner.on(", ");
    static final Joiner emptyJoiner = Joiner.on("");

    private Filer filer;
    private Elements elements;
    private Messager messager;
    private Types types;
    private FrameworkStatements statements;
    private int round = 0;

    @Override
    public void init(final ProcessingEnvironment env) {
        super.init(env);

        filer = env.getFiler();
        elements = env.getElementUtils();
        messager = env.getMessager();
        types = env.getTypeUtils();
        statements = new FrameworkStatements(types, elements);
        /**
         * Eclipse JDT does not preserve the original order of type fields, causing some Processor assumptions to fail.
         */
        if (env.getClass().getPackage().getName().startsWith("org.eclipse.jdt.")) {
            messager.printMessage(Diagnostic.Kind.WARNING,
                    "@AutoSerialize processor might not work properly in Eclipse");
        }
    }

    @Override
    public boolean process(Set<? extends TypeElement> typeElements, RoundEnvironment env) {
        messager.printMessage(Diagnostic.Kind.NOTE, String.format("processing round %d", round++));

        final List<SerializedType> processed = processElements(env.getElementsAnnotatedWith(AutoSerialize.class));

        for (final SerializedType p : processed) {
            if (!p.isValid(messager)) {
                messager.printMessage(Diagnostic.Kind.WARNING,
                        String.format("Might not be valid: %s", p.getElementType()));
            }

            final JavaFile output = p.asJavaFile();

            messager.printMessage(Diagnostic.Kind.NOTE,
                    String.format("Writing %s.%s", output.packageName, output.typeSpec.name));

            try {
                output.writeTo(filer);
            } catch (IOException e) {
                messager.printMessage(Diagnostic.Kind.ERROR, e.getMessage());
                return false;
            }
        }

        return false;
    }

    Map<String, Collection<SerializedType>> byPackage(Collection<SerializedType> processed) {
        final Map<String, Collection<SerializedType>> byPackage = new HashMap<>();

        for (final SerializedType p : processed) {
            Collection<SerializedType> group = byPackage.get(p.packageName);

            if (group == null) {
                group = new ArrayList<>();
                byPackage.put(p.packageName, group);
            }

            group.add(p);
        }

        return byPackage;
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return ImmutableSet.of(AutoSerialize.class.getName());
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    List<SerializedType> processElements(Set<? extends Element> elements) {
        final List<SerializedType> processed = new ArrayList<>();

        for (final Element element : elements) {
            messager.printMessage(Diagnostic.Kind.NOTE, String.format("Processing %s", element));
            processed.add(processElement(element));
        }

        return processed;
    }

    SerializedType processElement(Element element) {
        if (element.getKind() == ElementKind.INTERFACE) {
            return processInterface(element);
        }

        return processClass(element);
    }

    SerializedType processInterface(Element element) {
        final AutoSerialize annotation = element.getAnnotation(AutoSerialize.class);

        final String packageName = elements.getPackageOf(element).getQualifiedName().toString();
        final String serializerName = statements.serializerName(element);
        final String name = name(element, annotation);

        final TypeName elementType = TypeName.get(element.asType());
        final TypeName supertype = TypeName.get(statements.serializerFor(element.asType()));

        final List<SubType> subtypes = buildSubTypes(element);

        final FieldSpec serializer = FieldSpec.builder(supertype, "serializer", Modifier.FINAL).build();

        final TypeSpec.Builder generated = TypeSpec.classBuilder(serializerName);

        generated.addModifiers(Modifier.PUBLIC, Modifier.FINAL);
        generated.addSuperinterface(supertype);

        generated.addField(serializer);

        generated.addMethod(interfaceSerializeConstructor(elementType, serializer, subtypes));
        generated.addMethod(interfaceSerializeMethod(elementType, serializer));
        generated.addMethod(interfaceDeserializeMethod(elementType, serializer));

        final SerializedTypeFields fields = new SerializedTypeFields(annotation.orderById(),
                annotation.orderConstructorById());

        return new SerializedType(element, packageName, name, generated.build(), elementType, supertype, fields);
    }

    SerializedType processClass(final Element element) {
        final AutoSerialize autoSerialize = element.getAnnotation(AutoSerialize.class);

        final String packageName = elements.getPackageOf(element).getQualifiedName().toString();
        final String name = name(element, autoSerialize);

        final SerializedTypeFields serializedType = SerializedTypeFields.build(statements, element, autoSerialize);

        final TypeName elementType = TypeName.get(element.asType());
        final TypeName supertype = TypeName.get(statements.serializerFor(element.asType()));

        final TypeSpec.Builder generated = TypeSpec.classBuilder(statements.serializerName(element));

        for (final SerializedFieldType t : serializedType.getFieldTypes()) {
            generated.addField(t.getFieldSpec());
        }

        generated.addModifiers(Modifier.PUBLIC, Modifier.FINAL);
        generated.addSuperinterface(supertype);

        generated.addMethod(classSerializeConstructor(serializedType));
        generated.addMethod(classSerializeMethod(elementType, serializedType));
        generated.addMethod(classDeserializeMethod(elementType, serializedType, autoSerialize));

        return new SerializedType(element, packageName, name, generated.build(), elementType, supertype, serializedType);
    }

    List<SubType> buildSubTypes(Element element) {
        final AutoSerialize.SubTypes annotation = element.getAnnotation(AutoSerialize.SubTypes.class);

        if (annotation == null) {
            return ImmutableList.of();
        }

        final Set<Short> seenIds = new HashSet<>();
        final ImmutableList.Builder<SubType> subtypes = ImmutableList.builder();

        int offset = 0;
        int index = 0;

        for (final AutoSerialize.SubType s : annotation.value()) {
            final ClassName type = hackilyPullClassName(s);
            final short id = s.id() < 0 ? nextShort(index++) : s.id();

            if (!seenIds.add(id)) {
                throw new IllegalStateException(String.format("Conflicting subtype id (%d) defined for definition #%d",
                        id, offset));
            }

            subtypes.add(new SubType(type, id));
            offset++;
        }

        return subtypes.build();
    }

    short nextShort(int i) {
        if (i > Short.MAX_VALUE) {
            throw new IllegalStateException("Too many subtypes defined");
        }

        return (short) i;
    }

    ClassName hackilyPullClassName(AutoSerialize.SubType annotation) {
        try {
            return ClassName.get(annotation.value());
        } catch (final MirroredTypeException e) {
            return (ClassName) TypeName.get(e.getTypeMirror());
        }
    }

    MethodSpec interfaceSerializeConstructor(TypeName elementType, FieldSpec serializer, final List<SubType> subtypes) {
        final ClassName list = ClassName.get(List.class);
        final ClassName typeMapping = ClassName.get(TypeMapping.class);
        final ClassName arrayList = ClassName.get(ArrayList.class);

        final ParameterSpec framework = ParameterSpec.builder(SerializerFramework.class, FRAMEWORK_NAME)
                .addModifiers(Modifier.FINAL).build();

        final MethodSpec.Builder b = MethodSpec.constructorBuilder();
        b.addModifiers(Modifier.PUBLIC);

        b.addParameter(framework);

        b.addStatement("final $T<$T<? extends $T, $T>> mappings = new $T<>()", list, typeMapping, elementType,
                elementType, arrayList);

        for (final SubType subtype : subtypes) {
            final ClassName serializerType = statements.serializerClassFor(subtype.type);

            b.addStatement("mappings.add($N.<$T, $T>type($L, $T.class, new $T($N)))", framework, subtype.type,
                    elementType, subtype.id, subtype.type, serializerType, framework);
        }

        b.addStatement("$N = $N.subtypes(mappings)", serializer, framework);
        return b.build();
    }

    MethodSpec interfaceSerializeMethod(TypeName valueType, FieldSpec serializer) {
        final ParameterSpec buffer = ParameterSpec.builder(SerialWriter.class, "buffer").addModifiers(Modifier.FINAL)
                .build();

        final ParameterSpec value = ParameterSpec.builder(valueType, "value").addModifiers(Modifier.FINAL).build();

        final MethodSpec.Builder b = buildSerializeMethod(buffer, value);

        b.addStatement("$N.serialize($N, $N)", serializer, buffer, value);

        return b.build();
    }

    MethodSpec interfaceDeserializeMethod(TypeName returnType, FieldSpec serializer) {
        final ParameterSpec buffer = ParameterSpec.builder(SerialReader.class, "buffer").addModifiers(Modifier.FINAL)
                .build();

        final MethodSpec.Builder b = buildDeserializeMethod(returnType, buffer);

        b.addStatement("return $N.deserialize($N)", serializer, buffer);

        return b.build();
    }

    MethodSpec classSerializeConstructor(final SerializedTypeFields serialized) {
        final ParameterSpec framework = ParameterSpec.builder(SerializerFramework.class, FRAMEWORK_NAME)
                .addModifiers(Modifier.FINAL).build();

        final MethodSpec.Builder b = MethodSpec.constructorBuilder();
        b.addModifiers(Modifier.PUBLIC);
        b.addParameter(framework);

        for (final SerializedFieldType t : serialized.getOrderedFieldTypes()) {
            if (t.getProvidedParameterSpec().isPresent()) {
                b.addParameter(t.getProvidedParameterSpec().get());
            }
        }

        for (final SerializedFieldType fieldType : serialized.getOrderedFieldTypes()) {
            if (fieldType.getProvidedParameterSpec().isPresent()) {
                b.addStatement("$N = $N", fieldType.getFieldSpec(), fieldType.getProvidedParameterSpec().get());
                continue;
            }

            final FrameworkMethodBuilder builder = new FrameworkMethodBuilder() {
                @Override
                public void assign(final String statement, final List<Object> arguments) {
                    b.addStatement(String.format("$N = %s", statement),
                            ImmutableList.builder().add(fieldType.getFieldSpec()).addAll(arguments).build().toArray());
                }
            };

            statements.resolveStatement(boxedType(fieldType.getTypeElement().asType()), framework).writeTo(builder);
        }

        return b.build();
    }

    MethodSpec classSerializeMethod(final TypeName valueType, final SerializedTypeFields serialized) {
        final ParameterSpec buffer = ParameterSpec.builder(SerialWriter.class, "buffer").addModifiers(Modifier.FINAL)
                .build();

        final ParameterSpec value = ParameterSpec.builder(valueType, "value").addModifiers(Modifier.FINAL).build();

        final MethodSpec.Builder b = buildSerializeMethod(buffer, value);

        for (final SerializedField field : serialized.getOrderedFields()) {
            b.addStatement("$N.serialize($N, $N.$L())", field.getFieldType().getFieldSpec(), buffer, value,
                    field.getAccessor());
        }

        return b.build();
    }

    MethodSpec classDeserializeMethod(TypeName returnType, SerializedTypeFields serializedType,
            AutoSerialize autoSerialize) {
        final ParameterSpec buffer = ParameterSpec.builder(SerialReader.class, "buffer").addModifiers(Modifier.FINAL)
                .build();

        final MethodSpec.Builder b = buildDeserializeMethod(returnType, buffer);

        for (final SerializedField field : serializedType.getOrderedFields()) {
            final TypeName fieldType = field.getFieldType().getFieldType();
            final FieldSpec fieldSpec = field.getFieldType().getFieldSpec();
            b.addStatement("final $T $L = $N.deserialize($N)", fieldType, field.getVariableName(), fieldSpec, buffer);
        }

        if (autoSerialize.useBuilder()) {
            classDeserializeBuilder(returnType, b, serializedType.getFields(), autoSerialize);
        } else {
            b.addStatement("return new $T($L)", returnType,
                    parameterJoiner.join(serializedType.getConstructorVariables()));
        }

        return b.build();
    }

    void classDeserializeBuilder(TypeName returnType, Builder b, List<SerializedField> variables,
            AutoSerialize autoSerialize) {
        final ImmutableList.Builder<String> builders = ImmutableList.builder();
        final ImmutableList.Builder<Object> parameters = ImmutableList.builder();

        parameters.add(returnType);

        for (final SerializedField f : variables) {
            final String setter = builderSetter(f, autoSerialize);
            builders.add(String.format(".%s($L)", setter));
            parameters.add(f.getVariableName());
        }

        b.addStatement(String.format("return $T.builder()%s.build()", emptyJoiner.join(builders.build())), parameters
                .build().toArray());
    }

    String builderSetter(final SerializedField f, AutoSerialize autoSerialize) {
        if (autoSerialize.useBuilderSetter()) {
            return "set" + CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_CAMEL, f.getFieldName());
        }

        return f.getFieldName();
    }

    MethodSpec.Builder buildDeserializeMethod(final TypeName elementType, final ParameterSpec buffer) {
        final MethodSpec.Builder b = MethodSpec.methodBuilder("deserialize");

        b.addModifiers(Modifier.PUBLIC);
        b.returns(elementType);
        b.addAnnotation(Override.class);
        b.addParameter(buffer);
        b.addException(IOException.class);

        return b;
    }

    MethodSpec.Builder buildSerializeMethod(final ParameterSpec buffer, final ParameterSpec value) {
        final MethodSpec.Builder b = MethodSpec.methodBuilder("serialize");

        b.addModifiers(Modifier.PUBLIC);
        b.returns(TypeName.VOID);
        b.addAnnotation(Override.class);
        b.addParameter(buffer);
        b.addParameter(value);
        b.addException(IOException.class);

        return b;
    }

    TypeMirror boxedType(TypeMirror type) {
        if (type instanceof PrimitiveType) {
            return types.boxedClass((PrimitiveType)type).asType();
        }

        return type;
    }

    String name(Element element, AutoSerialize annotation) {
        if (!"".equals(annotation.name())) {
            return annotation.name();
        }

        return element.getSimpleName().toString();
    }
}