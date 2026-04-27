import com.graphhopper.reader.ReaderElement;
import com.graphhopper.reader.ReaderNode;
import com.graphhopper.reader.osm.SkipOptions;
import com.graphhopper.reader.osm.pbf.PbfReader;
import com.graphhopper.reader.osm.pbf.Sink;

import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class PlaceIndexBuilder {
    private static final List<String> CATEGORY_KEYS = Arrays.asList(
            "amenity", "healthcare", "shop", "tourism", "leisure",
            "aeroway", "railway", "public_transport", "office",
            "military", "place", "historic", "man_made"
    );

    private static final class PlaceRecord {
        final long id;
        final String name;
        final String normalizedName;
        final String categoryKey;
        final String categoryValue;
        final double lat;
        final double lon;

        PlaceRecord(long id, String name, String normalizedName, String categoryKey, String categoryValue, double lat, double lon) {
            this.id = id;
            this.name = name;
            this.normalizedName = normalizedName;
            this.categoryKey = categoryKey;
            this.categoryValue = categoryValue;
            this.lat = lat;
            this.lon = lon;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: java PlaceIndexBuilder <input.osm.pbf> <output.json>");
            System.exit(1);
        }

        Path inputPath = Path.of(args[0]);
        Path outputPath = Path.of(args[1]);
        Files.createDirectories(outputPath.getParent());

        List<PlaceRecord> places = new ArrayList<>();
        try (FileInputStream inputStream = new FileInputStream(inputPath.toFile())) {
            PbfReader reader = new PbfReader(
                    inputStream,
                    new Sink() {
                        @Override
                        public void process(ReaderElement element) {
                            if (!(element instanceof ReaderNode)) {
                                return;
                            }
                            ReaderNode node = (ReaderNode) element;
                            PlaceRecord place = buildPlaceRecord(node);
                            if (place != null) {
                                places.add(place);
                            }
                        }

                        @Override
                        public void complete() {
                            System.out.println("Collected places: " + places.size());
                        }
                    },
                    1,
                    new SkipOptions(false, true, true)
            );
            reader.run();
            reader.close();
        }

        try (Writer writer = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(outputPath), StandardCharsets.UTF_8))) {
            writer.write("[\n");
            for (int i = 0; i < places.size(); i++) {
                PlaceRecord place = places.get(i);
                writer.write("  {");
                writer.write("\"id\":" + place.id + ",");
                writer.write("\"name\":\"" + escape(place.name) + "\",");
                writer.write("\"normalizedName\":\"" + escape(place.normalizedName) + "\",");
                writer.write("\"categoryKey\":\"" + escape(place.categoryKey) + "\",");
                writer.write("\"categoryValue\":\"" + escape(place.categoryValue) + "\",");
                writer.write("\"lat\":" + place.lat + ",");
                writer.write("\"lon\":" + place.lon);
                writer.write("}");
                if (i < places.size() - 1) {
                    writer.write(",");
                }
                writer.write("\n");
            }
            writer.write("]\n");
        }
    }

    private static PlaceRecord buildPlaceRecord(ReaderNode node) {
        Map<String, Object> tags = node.getTags();
        if (tags == null || tags.isEmpty()) {
            return null;
        }

        String name = trimToNull(tagValue(tags, "name"));
        String categoryKey = null;
        String categoryValue = null;
        for (String key : CATEGORY_KEYS) {
            String value = trimToNull(tagValue(tags, key));
            if (value != null) {
                categoryKey = key;
                categoryValue = value;
                break;
            }
        }

        if (name == null && categoryValue == null) {
            return null;
        }

        if (name == null && !isUsefulUnnamedCategory(categoryValue)) {
            return null;
        }

        String normalizedName = normalize(name != null ? name : categoryValue);
        return new PlaceRecord(
                node.getId(),
                name != null ? name : categoryValue,
                normalizedName,
                categoryKey != null ? categoryKey : "",
                categoryValue != null ? categoryValue : "",
                node.getLat(),
                node.getLon()
        );
    }

    private static boolean isUsefulUnnamedCategory(String categoryValue) {
        if (categoryValue == null) {
            return false;
        }
        return Arrays.asList(
                "hospital", "fuel", "police", "fire_station", "bus_station",
                "aerodrome", "airport", "station", "pharmacy", "clinic",
                "military", "town", "village", "suburb", "neighbourhood"
        ).contains(categoryValue);
    }

    private static String tagValue(Map<String, Object> tags, String key) {
        Object value = tags.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
