/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.corpus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;


import de.cuioss.tools.logging.CuiLogger;
import de.planmarshall.core.role.corpus.CorpusImport;
import de.planmarshall.core.role.corpus.CorpusValidator;
import de.planmarshall.core.role.corpus.CorpusViolation;
import de.planmarshall.core.role.corpus.ImportResult;
import de.planmarshall.core.role.corpus.ImportedItem;
import de.planmarshall.core.role.corpus.ImportedItemCodec;
import de.planmarshall.core.role.corpus.SourceManifest;
import de.planmarshall.core.role.corpus.SourceManifest.FetchKind;
import de.planmarshall.core.role.corpus.SourceManifestCodec;
import de.planmarshall.core.role.corpus.SourceOverlay;
import de.planmarshall.core.role.corpus.SourceOverlayCodec;
import de.planmarshall.core.role.corpus.SourceRepository;
import de.planmarshall.runtime.corpus.HuggingFaceSourceRepository;
import de.planmarshall.runtime.corpus.LfsBatchClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * The corpus test of the model verification corpus (PM-WORK-6, PM-TEST-6): imports every source of
 * {@code test/model/verification/} from its repository at the pinned commit and validates the corpus together with the
 * generated items.
 * <p>
 * Hazard: the corpus holds only manifests and overlays of the public datasets; whether they yield the items they
 * declare is known only after a real fetch. A source that cannot be fetched, fails its hash, or whose overlay does not
 * generate its rows leaves its topics short without any other check noticing. The test composes the import of
 * {@code core.role.corpus} with the fetch of {@code pm-runtime}, the repository picked by the manifests' {@code fetch}
 * member, and fails on a {@code FAILED} or {@code PARTIAL} import, on any violation of the validator, on a topic whose
 * generated items differ in number from its overlay rows, on a closed item with two equal choices, and on an overlay
 * row whose question and facts name no field of its row. It does no fetch, hashing or row parsing of its own.
 * <p>
 * It needs {@code huggingface.co} and runs with the Maven profile {@code corpus} only, which passes the repository root
 * as {@code pm.root} and the cache root below the build directory as {@code pm.corpus.cache}; every other build leaves
 * the tag {@code corpus} out.
 */
@Tag("corpus")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("The corpus sources against the real datasets")
class CorpusSourcesTest {

    private static final CuiLogger LOGGER = new CuiLogger(CorpusSourcesTest.class);

    private static final String CORPUS = "test/model/verification";
    private static final String JSON = ".json";

    private Path corpusRoot;
    private Path cacheRoot;
    private List<SourceOverlay> overlays;
    private ImportResult result;
    private List<CorpusViolation> violations;

    @BeforeAll
    void importSources() throws IOException {
        var root = System.getProperty("pm.root");
        var cache = System.getProperty("pm.corpus.cache");
        assertNotNull(root, "pm.root is not set: run the test with the Maven profile corpus");
        assertNotNull(cache, "pm.corpus.cache is not set: run the test with the Maven profile corpus");
        corpusRoot = Path.of(root).resolve(CORPUS);
        cacheRoot = Path.of(cache);
        var directories = sourceDirectories(corpusRoot);
        overlays = overlays(directories);
        var corpusImport = new CorpusImport(repository(manifests(directories)), new LfsBatchClient());

        result = corpusImport.run(corpusRoot, cacheRoot);
        violations = new CorpusValidator(corpusRoot).validate(cacheRoot);
        LOGGER.debug("Imported %s items per source, verdict %s, %s violations", counts(result), result.verdict(),
                violations.size());
    }

    @Test
    @DisplayName("the import covers every source")
    void importIsComplete() {
        var missed = result.missed().stream()
                .map(source -> "%s: %s%s: %s".formatted(source.name(), source.reason().wireName(),
                        source.file() == null ? "" : " in " + source.file(), source.detail()))
                .collect(Collectors.joining("\n"));

        assertEquals(ImportResult.Verdict.COMPLETE, result.verdict(), "missed sources:\n" + missed);
        assertFalse(result.covered().isEmpty(), "the corpus has no source");
    }

    @Test
    @DisplayName("the corpus and the generated items have no violation")
    void noViolation() {
        var listed = violations.stream().map(CorpusViolation::toString).collect(Collectors.joining("\n"));

        assertEquals(List.of(), violations, "violations:\n" + listed);
    }

    @Test
    @DisplayName("every topic has as many generated items as overlay rows")
    void itemsPerTopic() throws Exception {
        var declared = new TreeMap<String, Integer>();
        overlays.forEach(overlay -> overlay.rows().forEach(row -> declared.merge(row.topic(), 1, Integer::sum)));

        var generated = new TreeMap<String, Integer>();
        for (var topic : declared.keySet()) {
            generated.put(topic, itemFiles(cacheRoot.resolve(CorpusImport.ITEMS).resolve(topic)).size());
        }

        assertEquals(declared, generated, "items per topic: overlay rows against generated items");
    }

    @Test
    @DisplayName("every generated closed item has pairwise distinct choices")
    void closedItemsHaveDistinctChoices() throws Exception {
        var repeated = new ArrayList<String>();
        for (var item : generatedItems()) {
            if (item.grading().method().isClosed()) {
                var choices = item.input().choices();
                if (choices == null || new HashSet<>(choices).size() != choices.size()) {
                    repeated.add(item.id() + ": " + choices);
                }
            }
        }

        assertEquals(List.of(), repeated, "closed items whose choices are missing or repeat");
    }

    @Test
    @DisplayName("every overlay row's question or facts names a field of its row")
    void everyRowNamesAField() {
        var unnamed = new ArrayList<String>();
        for (var overlay : overlays) {
            for (var row : overlay.rows()) {
                if (!namesField(row.question()) && !namesField(row.facts())) {
                    unnamed.add(overlay.source() + ": " + row.itemId());
                }
            }
        }

        assertEquals(List.of(), unnamed, "overlay rows whose question and facts name no field of the row");
    }

    /**
     * Picks the fetch of the sources. {@link CorpusImport} takes one repository, so every manifest must name the same
     * fetch; a Hugging Face dataset is read by {@link HuggingFaceSourceRepository}, and no other kind is composed here.
     */
    private static SourceRepository repository(List<SourceManifest> manifests) {
        var kinds = manifests.stream().map(SourceManifest::fetch).collect(Collectors.toSet());
        if (!Set.of(FetchKind.HUGGINGFACE).equals(kinds)) {
            fail("the sources name the fetch %s; the corpus test composes huggingface only, one kind for all sources"
                    .formatted(kinds.stream().map(FetchKind::wireName).sorted().toList()));
        }
        return new HuggingFaceSourceRepository();
    }

    private static List<Path> sourceDirectories(Path corpus) throws IOException {
        try (Stream<Path> entries = Files.list(corpus.resolve(CorpusImport.SOURCES))) {
            return entries.filter(Files::isDirectory).sorted().toList();
        }
    }

    private static List<SourceManifest> manifests(List<Path> directories) throws IOException {
        var manifests = new ArrayList<SourceManifest>();
        for (var directory : directories) {
            var file = directory.resolve(CorpusImport.MANIFEST);
            manifests.add(SourceManifestCodec.read(file, Files.readAllBytes(file)));
        }
        return manifests;
    }

    /**
     * @return the overlays of the sources that have one; a missing overlay misses its source in the import and is a
     *         violation of the validator, both reported by the tests above
     */
    private static List<SourceOverlay> overlays(List<Path> directories) throws IOException {
        var overlays = new ArrayList<SourceOverlay>();
        for (var directory : directories) {
            var file = directory.resolve(CorpusImport.OVERLAY);
            if (Files.isRegularFile(file)) {
                overlays.add(SourceOverlayCodec.read(file, Files.readAllBytes(file)));
            }
        }
        return overlays;
    }

    private List<ImportedItem> generatedItems() throws IOException {
        var items = new ArrayList<ImportedItem>();
        var itemsRoot = cacheRoot.resolve(CorpusImport.ITEMS);
        try (Stream<Path> walk = Files.walk(itemsRoot)) {
            for (var file : walk.filter(path -> path.toString().endsWith(JSON)).sorted().toList()) {
                items.add(ImportedItemCodec.read(file, Files.readAllBytes(file)));
            }
        }
        return items;
    }

    /** @return the item files directly below a topic directory; none if the directory does not exist */
    private static List<Path> itemFiles(Path topic) throws IOException {
        if (!Files.isDirectory(topic)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(topic)) {
            return entries.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(JSON)).toList();
        }
    }

    /**
     * @param template a template of an overlay row
     * @return {@code true} if the template holds a placeholder {@code {field}}; a doubled brace is a literal one
     */
    private static boolean namesField(String template) {
        var length = template.length();
        for (var index = 0; index < length; index++) {
            var character = template.charAt(index);
            var doubled = index + 1 < length && template.charAt(index + 1) == character;
            if ((character == '{' || character == '}') && doubled) {
                index++;
            } else if (character == '{') {
                var close = template.indexOf('}', index + 1);
                return close > index + 1;
            }
        }
        return false;
    }

    /** @return the number of generated items per covered source */
    private static Map<String, Integer> counts(ImportResult imported) {
        return imported.covered().stream()
                .collect(Collectors.toMap(ImportResult.CoveredSource::name, ImportResult.CoveredSource::items,
                        Integer::sum, TreeMap::new));
    }
}
