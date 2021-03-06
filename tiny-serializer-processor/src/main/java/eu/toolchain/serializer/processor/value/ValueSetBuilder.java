package eu.toolchain.serializer.processor.value;

import com.google.common.collect.ImmutableList;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.ParameterSpec;
import com.squareup.javapoet.TypeName;
import eu.toolchain.serializer.processor.AutoSerializeUtils;
import eu.toolchain.serializer.processor.Naming;
import eu.toolchain.serializer.processor.unverified.Unverified;
import lombok.RequiredArgsConstructor;

import javax.lang.model.element.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
public class ValueSetBuilder {
    final Naming fieldNaming = new Naming("s_");
    final Naming providerNaming = new Naming("p_");
    final Naming variableNaming = new Naming("v_");
    final Naming isSetVariableNaming = new Naming("i_");

    final List<ValueType> types = new ArrayList<>();
    final List<Unverified<Value>> unverifiedValues = new ArrayList<>();

    final AutoSerializeUtils utils;

    public void add(Unverified<ValueSpecification> unverifiedValueSpecification) {
        unverifiedValues.add(unverifiedValueSpecification.map((spec) -> {
            final TypeName valueType = TypeName.get(spec.getValueType());
            final TypeName serializerType = TypeName.get(utils.serializerFor(spec.getValueType()));

            final ValueTypeIdentifier identifier =
                new ValueTypeIdentifier(valueType, spec.isProvided(), spec.getProviderName());

            final ValueType type;
            final Optional<ValueType> found;

            if ((found = types
                .stream()
                .filter((t) -> t.getIdentifier().equals(identifier))
                .findFirst()).isPresent()) {
                type = found.get();
            } else {
                final String typeFieldName = fieldNaming.forType(valueType, spec.isProvided());

                final FieldSpec fieldSpec = FieldSpec
                    .builder(serializerType, typeFieldName)
                    .addModifiers(Modifier.FINAL)
                    .build();

                final Optional<ParameterSpec> providedParameterSpec;

                if (spec.isProvided()) {
                    final String uniqueProviderName;

                    if (spec.getProviderName().isPresent()) {
                        uniqueProviderName = providerNaming.forName(spec.getProviderName().get());
                    } else {
                        uniqueProviderName = providerNaming.forType(valueType, false);
                    }

                    providedParameterSpec = Optional.of(ParameterSpec
                        .builder(serializerType, uniqueProviderName, Modifier.FINAL)
                        .build());
                } else {
                    providedParameterSpec = Optional.empty();
                }

                type = new ValueType(identifier, spec.getValueType(), valueType, fieldSpec,
                    providedParameterSpec, spec.getId(), spec.isOptional());
                types.add(type);
            }

            return new Value(type, spec.getValueName(), spec.getAccessor(),
                variableNaming.forName(spec.getValueName()),
                isSetVariableNaming.forName(spec.getValueName()), spec.getId(),
                spec.getConstructorOrder());
        }));
    }

    public Unverified<ValueSet> build(boolean isOrdereById, boolean isOrderConstructorById) {
        return Unverified.combine(unverifiedValues).map((values) -> {
            return new ValueSet(isOrdereById, isOrderConstructorById, ImmutableList.copyOf(types),
                ImmutableList.copyOf(values));
        });
    }
}
