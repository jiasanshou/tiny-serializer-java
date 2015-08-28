package eu.toolchain.serializer.perftests;

import java.util.List;
import java.util.Map;
import java.util.Set;

import eu.toolchain.serializer.AutoSerialize;
import lombok.Data;


@Data
@AutoSerialize(fieldBased = true, failOnMissing = true)
public class ImmutableSerializedObject {
    final int version;
    final String field;
    final Map<String, String> map;
    final List<String> list;
    final Map<String, List<String>> optionalMap;
    final Set<Long> set;
}