/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.jboss.galleon.universe.maven.MavenArtifact;
import org.jboss.galleon.universe.maven.repo.MavenRepoManager;
import org.wildfly.catalog.Resources.Variant;
import static org.wildfly.catalog.TemplateUtils.ENGINE;
import org.wildfly.glow.maven.MavenResolver;

public class Main {

    private static final String VERSION_PROP = "wildfly-version";
    private static final String RELEASE_PROP = "release";
    private static final String DEFAULT_VARIANT = "default";
    private static final String REPLACE_WILDFLY_VERSION = "###REPLACE_WILDFLY_VERSION###";
    private static final String REPLACE_JSON_URL = "###REPLACE_JSON_URL###";
    private static final String REPLACE_LATEST_INDEX_ENTRY = "<!-- ####REPLACE_LAST_VERSION#### -->";
    private static final String REPLACE_WILDFLY_VARIANT = "###VARIANT_DESCRIPTION###";
    public static void main(String[] args) throws Exception {
        String wildflyVersion = System.getProperty(VERSION_PROP);
        if (wildflyVersion == null) {
            throw new Exception("-D" + VERSION_PROP + "=<version> must be set");
        }
        MavenRepoManager resolver = MavenResolver.newMavenResolver();
        boolean release = Boolean.getBoolean(RELEASE_PROP);
        Path rootDirectory = release ? Paths.get("../docs") : Paths.get("target/catalog");
        Path wildflyVersionDirectory = rootDirectory.resolve(wildflyVersion);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(String.join("", patchFile("wildfly-catalog-metadata.json", wildflyVersion, null, release, null, null)));

        // Glow rules description
        Properties glowRulesDescriptions = new Properties();
        String rulesURL = node.get("glowRulesDescriptions").asText();
        try (InputStream in = new URL(rulesURL).openStream()) {
            glowRulesDescriptions.load(in);
        }
        Map<String, Map<String, JsonNode>> categories = new TreeMap<>();
        String baseMetadataUrl = node.get("baseMetadataURL").asText();
        JsonNode variantsList = mapper.readTree(new URI(baseMetadataUrl + "variants.json").toURL());
        List<Variant> variants = new ArrayList<>();
        ArrayNode variantNodes = (ArrayNode) variantsList.get("variants");
        // Add the default variant
        ObjectNode defaultVariant = mapper.createObjectNode();
        defaultVariant.put("directory", DEFAULT_VARIANT);
        defaultVariant.put("description", "WildFly");
        variantNodes.insert(0, defaultVariant);
        Iterator<JsonNode> variantsIt = variantNodes.elements();
        while (variantsIt.hasNext()) {
            JsonNode variantNode = variantsIt.next();
            String variantDir = variantNode.get("directory").asText();
            String variantDescription = variantNode.get("description").asText();
            ObjectNode target = mapper.createObjectNode();
            target.put("description", variantDescription + " " + wildflyVersion + " " + node.get("description").asText());
            target.set("documentation", node.get("documentation"));
            target.set("legend", node.get("legend"));
            variants.add(new Variant(variantDir, variantDescription));
            JsonNode fpList = mapper.readTree(new URI(baseMetadataUrl + (variantDir.equals(DEFAULT_VARIANT) ? "" : variantDir) + "/" + "feature-packs.json").toURL());
            Path variantDirectory = wildflyVersionDirectory.resolve(variantDir);
            Path targetDirectory = variantDirectory.toAbsolutePath();
            Path featurePacksTargetDirectory = targetDirectory.resolve("featurePacks");
            Files.createDirectories(featurePacksTargetDirectory);
            ArrayNode fps = (ArrayNode) fpList.get("featurePacks");
            Iterator<JsonNode> it = fps.elements();
            ArrayNode featurePacks = mapper.createArrayNode();
            target.set("featurePacks", featurePacks);
            while (it.hasNext()) {
                ObjectNode fpNode = mapper.createObjectNode();
                featurePacks.add(fpNode);
                String fp = it.next().asText();
                fpNode.put("mavenCoordinates", fp);
                String[] coords = fp.split(":");
                String groupId = coords[0];
                String artifactId = coords[1];
                String version = coords[2];
                Path docFile = resolveMavenArtifact(resolver, groupId, artifactId, version, "doc", "zip");
                String directoryName = (coords[0] + '_' + artifactId);
                Path fpDirectory = featurePacksTargetDirectory.resolve(directoryName);
                unzip(docFile, fpDirectory);
                Path metadataFile = fpDirectory.resolve("doc/META-INF/metadata.json");
                Path modelFile = fpDirectory.resolve("doc/META-INF/management-api.json");
                Path logMessages = fpDirectory.resolve("doc/log-message-reference.html");
                JsonNode subCatalog = mapper.readTree(metadataFile.toFile().toURI().toURL());
                String name = subCatalog.get("name").asText();
                fpNode.put("name", name);
                fpNode.put("description", subCatalog.get("description").asText());
                fpNode.putIfAbsent("licenses", subCatalog.get("licenses"));
                fpNode.put("projectURL", subCatalog.get("url").asText());
                fpNode.put("scmURL", subCatalog.get("scm-url").asText());
                ArrayNode layersArray = (ArrayNode) subCatalog.get("layers");
                Iterator<JsonNode> layers = layersArray.elements();
                ArrayNode layersArrayTarget = mapper.createArrayNode();
                fpNode.set("layers", layersArrayTarget);
                Set<String> layersSet = new TreeSet<>();
                while (layers.hasNext()) {
                    JsonNode layer = layers.next();
                    if (!isInternalLayer(layer)) {
                        layersSet.add(layer.get("name").asText());
                    }
                }
                for (String n : layersSet) {
                    layersArrayTarget.add(n);
                }

                if (Files.exists(modelFile)) {
                    fpNode.put("modelReference", "featurePacks/" + directoryName + "/doc/reference/index.html");
                }
                if (Files.exists(logMessages)) {
                    fpNode.put("logMessagesReference", "featurePacks/" + directoryName + "/doc/" + logMessages.getFileName().toString());
                }
                generateCatalog(subCatalog, glowRulesDescriptions, categories, mapper, featurePacksTargetDirectory);
            }
            ArrayNode categoriesArray = mapper.createArrayNode();
            target.putIfAbsent("categories", categoriesArray);
            for (Entry<String, Map<String, JsonNode>> entry : categories.entrySet()) {
                String categoryName = entry.getKey();
                ObjectNode category = mapper.createObjectNode();
                category.put("name", categoryName);
                ArrayNode categoryLayers = mapper.createArrayNode();
                category.put("functionalities", categoryLayers);
                for (Entry<String, JsonNode> layersInCategory : entry.getValue().entrySet()) {
                    categoryLayers.add(layersInCategory.getValue());
                }
                categoriesArray.add(category);
            }
            Path json = targetDirectory.resolve("wildfly-catalog.json");
            Files.deleteIfExists(json);
            mapper.writerWithDefaultPrettyPrinter().writeValue(json.toFile(), target);
            Path viewer = targetDirectory.resolve("index.html");
            Files.deleteIfExists(viewer);
            Files.write(viewer, patchFile("wildfly-catalog-viewer.html", wildflyVersion, json, release, variantDir, variantDescription));
        }
        String variantsIndexContent = ENGINE.getTemplate("variants")
                .data("variants", variants)
                .render();
        Files.write(wildflyVersionDirectory.resolve("index.html"), variantsIndexContent.getBytes());
        if (release) {
            String newEntry = "<li><a href=\"" + wildflyVersion + "/index.html\">" + wildflyVersion + "</a></li>";
            // Update index
            Path indexFile = rootDirectory.resolve("index.html").toAbsolutePath();
            List<String> lines = Files.readAllLines(indexFile);
            //Check if the index already exists
            boolean exists = false;
            for (String line : lines) {
                if (line.contains(newEntry)) {
                    exists = true;
                    break;
                }
            }
            if (exists) {
                System.out.println("Catalog for WildFly " + wildflyVersion + " already exists in the index, index not updated.");
            } else {
                List<String> targetLines = new ArrayList<>();
                for (String line : lines) {
                    if (line.contains(REPLACE_LATEST_INDEX_ENTRY)) {
                        targetLines.add(newEntry);
                        targetLines.add(REPLACE_LATEST_INDEX_ENTRY);
                    } else {
                        targetLines.add(line);
                    }
                }
                Files.deleteIfExists(indexFile);
                Files.write(indexFile, targetLines);
            }
        }
        System.out.println("Catalog has been generated in " + rootDirectory.toAbsolutePath());
    }

    private static List<String> patchFile(String resource, String wildflyVersion, Path jsonFile, boolean release, String variantDir, String variantDescription) throws Exception {
        String uri = "https://wildfly-extras.github.io/wildfly-catalog/" + wildflyVersion + "/" + variantDir + "/wildfly-catalog.json";
        if (jsonFile != null) {
            if (!release) {
                uri = jsonFile.toUri().toString();
            }
        }
        try (InputStream stream = Main.class.getResourceAsStream(resource)) {
            try (InputStreamReader inputStreamReader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                try (BufferedReader bufferedReader = new BufferedReader(inputStreamReader)) {
                    List<String> lines = bufferedReader.lines().collect(Collectors.toList());
                    List<String> targetLines = new ArrayList<>();
                    for (String line : lines) {
                        if (line.contains(REPLACE_JSON_URL)) {
                            line = line.replace(REPLACE_JSON_URL, uri);
                        }
                        if (line.contains(REPLACE_WILDFLY_VERSION)) {
                            line = line.replace(REPLACE_WILDFLY_VERSION, wildflyVersion);
                        }
                        if (line.contains(REPLACE_WILDFLY_VARIANT)) {
                            line = line.replace(REPLACE_WILDFLY_VARIANT, variantDescription);
                        }
                        targetLines.add(line);
                    }
                    return targetLines;
                }
            }
        }
    }

    private static void generateCatalog(JsonNode subCatalog, Properties glowRulesDescriptions,
            Map<String, Map<String, JsonNode>> categories, ObjectMapper mapper, Path wildscribeTargetDirectory) throws Exception {
        String groupId = subCatalog.get("groupId").asText();
        String artifactId = subCatalog.get("artifactId").asText();
        String version = subCatalog.get("version").asText();
        String fp = groupId + ":" + artifactId + ":" + version;

        ArrayNode layersArray = (ArrayNode) subCatalog.get("layers");
        Iterator<JsonNode> layers = layersArray.elements();
        while (layers.hasNext()) {
            ObjectNode layer = (ObjectNode) layers.next();
            layer.put("feature-pack", fp);
            String layerName = layer.get("name").asText();
            ArrayNode props = ((ArrayNode) layer.get("properties"));
            String category = null;
            String description = null;
            String note = null;
            String addOn = null;
            // For now we have feature-packs that have not been regenerated with the support for stability.
            JsonNode stab = layer.get("stability");
            String stability = stab == null ? null : stab.asText();
            List<JsonNode> discoveryRules = new ArrayList<>();
            if (props != null) {
                Iterator<JsonNode> properties = props.elements();
                while (properties.hasNext()) {
                    ObjectNode prop = (ObjectNode) properties.next();
                    String name = prop.get("name").asText();
                    if (name.equals("org.wildfly.category")) {
                        category = prop.get("value").asText();
                        continue;
                    }
                    if (name.equals("org.wildfly.description")) {
                        description = prop.get("value").asText();
                        continue;
                    }
                    if (name.equals("org.wildfly.note")) {
                        note = prop.get("value").asText();
                        continue;
                    }
                    if (name.equals("org.wildfly.stability")) {
                        stability = prop.get("value").asText();
                        continue;
                    }
                    if (name.equals("org.wildfly.rule.add-on")) {
                        String val = prop.get("value").asText();
                        addOn = val.split(",")[1];
                        continue;
                    }
                    if (name.equals("org.wildfly.rule.kind")) {
                        String val = prop.get("value").asText();
                        if (val.equals("default-base-layer")) {
                            discoveryRules.add(prop);
                            setRuleDescription(name, glowRulesDescriptions, prop);
                        }
                        continue;
                    }
                    if (name.startsWith("org.wildfly.rule") && !name.startsWith("org.wildfly.rule.add-on")) {
                        discoveryRules.add(prop);
                        setRuleDescription(name, glowRulesDescriptions, prop);
                        continue;
                    }
                }
                layer.remove("properties");
            }
            if (category == null) {
                category = "Internal";
                // Internal without any content are not taken into account
                if (layer.get("managementModel").isEmpty()
                        && !layer.has("dependencies") && !layer.has("packages")) {
                    System.out.println("Internal with metadata only, ignoring " + layer.get("name").asText() + " of " + fp);
                    continue;
                }
            }
            if (description != null) {
                layer.put("description", description);
            }
            if (note != null) {
                layer.put("note", note);
            }
            if (addOn != null) {
                layer.put("glowAddOn", addOn);
            }
            if (stability == null) {
                layer.put("stability", "default");
            } else {
                layer.put("stability", stability);
            }
            if (!discoveryRules.isEmpty()) {
                ArrayNode rules = mapper.createArrayNode();
                rules.addAll(discoveryRules);
                layer.putIfAbsent("glowRules", rules);
            }
            layer.put("glowDiscoverable", !discoveryRules.isEmpty());
            Map<String, JsonNode> nodes = categories.get(category);
            if (nodes == null) {
                nodes = new TreeMap<>();
                categories.put(category, nodes);
            }
            if (nodes.containsKey(layerName)) {
                // add all dependencies
                JsonNode overriden = nodes.get(layerName);
                ArrayNode deps = (ArrayNode) layer.get("dependencies");
                if (deps != null) {
                    ArrayNode overridenDeps = (ArrayNode) overriden.get("dependencies");
                    deps.addAll(overridenDeps);
                }
            }
            if (layer.has("managementModel")) {
                navigate(wildscribeTargetDirectory, layer.get("managementModel"));
            }
            if (layer.has("configurations")) {
                navigate(wildscribeTargetDirectory, layer.get("configurations"));
            }
            nodes.put(layerName, layer);
        }
    }

    private static boolean isInternalLayer(JsonNode layer) {
        ArrayNode props = ((ArrayNode) layer.get("properties"));
        if (props == null) {
            return true;
        }
        Iterator<JsonNode> properties = props.elements();
        while (properties.hasNext()) {
            ObjectNode prop = (ObjectNode) properties.next();
            String name = prop.get("name").asText();
            if (name.equals("org.wildfly.category")) {
                return false;
            }
        }
        return true;
    }

    private static void setRuleDescription(String name, Properties props, ObjectNode node) {
        for (String k : props.stringPropertyNames()) {
            if (name.startsWith(k)) {
                node.put("ruleDescription", props.getProperty(k));
                node.put("valueDescription", props.getProperty(k + ".value"));
                break;
            }
        }
    }

    private static String formatURL(String url) {
        if ("/server-root=/".equals(url)) {
            url = "";
        }
        url = url.replaceAll("=\\*", "");
        url = url.replaceAll("=", "/");
        int i = url.lastIndexOf("@@@");
        String attributeName = null;
        if (i > 0) {
            attributeName = url.substring(i + 3, url.length());
            url = url.substring(0, i);
        }
        if (!url.endsWith("/")) {
            url += "/";
        }
        // We have an issue with jgroups, protocol and transport, no URL for them.
        if (url.equals("/subsystem/jgroups/stack/transport/") || url.equals("/subsystem/jgroups/stack/protocol/")) {
            url = "/subsystem/jgroups/stack/";
        }
        url += "index.html";
        if (attributeName != null) {
            url += "#" + attributeName;
        }
        return url;
    }

    private static void navigate(Path rootDir, JsonNode model) {
        if (model instanceof ArrayNode) {
            ArrayNode array = (ArrayNode) model;
            Iterator<JsonNode> it = array.elements();
            while (it.hasNext()) {
                navigate(rootDir, it.next());
            }
        } else {
            JsonNode n = model.get("_address");
            if (n != null) {
                String url = formatURL(n.asText());
                if (url == null) {
                    ((ObjectNode) model).remove("_address");
                } else {
                    String foundURL = findURL(rootDir, url);
                    if (foundURL == null) {
                        System.out.println("Url not found for " + url + " address was " + n.asText());
                        ((ObjectNode) model).remove("_address");
                    } else {
                        ((ObjectNode) model).put("_address", foundURL);
                    }
                }
            }
            Iterator<String> fields = model.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                JsonNode node = model.get(field);
                navigate(rootDir, node);
            }
        }
    }

    private static String findURL(Path rootDir, String path) {
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        String formattedPath = path;
        if (path.contains("#")) {
            int i = formattedPath.indexOf("#");
            formattedPath = formattedPath.substring(0, i);
        }
        for (File fpDir : rootDir.toFile().listFiles()) {
            Path pathFile = fpDir.toPath().resolve("doc").resolve("reference").resolve(formattedPath);
            if (Files.exists(pathFile)) {
                // No need for the fp root index.
                if (pathFile.getParent().equals(fpDir.toPath())) {
                    return null;
                }
                return "featurePacks/" + fpDir.getName() + "/doc/reference/" + path;
            }
        }
        return null;
    }

    /**
     * This call is thread safe, a new FS is created for each invocation.
     *
     * @param path The zip file.
     * @return A new FileSystem instance
     * @throws IOException in case of a failure
     */
    public static FileSystem newFileSystem(Path path) throws IOException {
        // The case here is explicitly done for Java 13+ where there is a newFileSystem(Path, Map) method as well.
        return FileSystems.newFileSystem(path, (ClassLoader) null);
    }

    public static void unzip(Path zipFile, Path targetDir) throws IOException {
        if (!Files.exists(targetDir)) {
            Files.createDirectories(targetDir);
        }
        try (FileSystem zipfs = newFileSystem(zipFile)) {
            for (Path zipRoot : zipfs.getRootDirectories()) {
                copyFromZip(zipRoot, targetDir);
            }
        }
    }

    public static void copyFromZip(Path source, Path target) throws IOException {
        Files.walkFileTree(source, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                final Path targetDir = target.resolve(source.relativize(dir).toString());
                try {
                    Files.copy(dir, targetDir);
                } catch (FileAlreadyExistsException e) {
                    if (!Files.isDirectory(targetDir)) {
                        throw e;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.copy(file, target.resolve(source.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void recursiveDelete(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    try {
                        Files.delete(file);
                    } catch (IOException ex) {
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e)
                        throws IOException {
                    if (e != null) {
                        // directory iteration failed
                        throw e;
                    }
                    try {
                        Files.delete(dir);
                    } catch (IOException ex) {
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
        }
    }

    private static Path resolveMavenArtifact(MavenRepoManager resolver, String groupId, String artifactId, String version, String classifier, String extension) throws Exception {
        MavenArtifact artifact = new MavenArtifact();
        artifact.setGroupId(groupId);
        artifact.setArtifactId(artifactId);
        artifact.setVersion(version);
        artifact.setClassifier(classifier);
        artifact.setExtension(extension);
        resolver.resolve(artifact);
        System.out.println("Maven resolution of " + artifact);
        return artifact.getPath();
    }
}
