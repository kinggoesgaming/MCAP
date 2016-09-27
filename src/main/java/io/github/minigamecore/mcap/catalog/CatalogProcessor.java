/*
 * This file is part of MCAP, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016 - 2016 MinigameCore <http://minigamecore.github.io>
 * Copyright (c) Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.github.minigamecore.mcap.catalog;

import static java.lang.String.format;
import static javax.lang.model.SourceVersion.latestSupported;
import static javax.tools.Diagnostic.Kind.NOTE;
import static javax.tools.Diagnostic.Kind.WARNING;
import static javax.tools.StandardLocation.CLASS_OUTPUT;
import static ninja.leaping.configurate.commented.SimpleCommentedConfigurationNode.root;

import io.github.minigamecore.mcap.annotate.catalog.Catalog;
import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.gson.GsonConfigurationLoader;
import ninja.leaping.configurate.loader.ConfigurationLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;

/**
 * The processor to analyze {@link Catalog}.
 */
@SupportedAnnotationTypes({CatalogProcessor.CATALOG})
public class CatalogProcessor extends AbstractProcessor {

    // Types and Annotation Qualified Names
    static final String CATALOG = "io.github.minigamecore.mcap.annotate.catalog.Catalog";
    private static final String CATALOG_TYPE = "org.spongepowered.api.CatalogType";

    // Configurate stuff
    private ConfigurationNode node = root();
    private Filer filer;

    // Statistics
    private int rounds = 0;
    private int successes = 0;
    private int failures = 0;

    private final List<Element> notTypes = new ArrayList<>();
    private final Map<Element, String> notSpecificTypes = new HashMap<>();
    private final Map<Element, String> duplicates = new HashMap<>();

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);

        // TODO initialization message
        filer = env.getFiler();
        node.getNode("version").setValue(1);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {

        if (env.processingOver()) {

            if (env.errorRaised()) {
                return false;
            }

            try {
                Path path = Paths.get(filer.createResource(CLASS_OUTPUT, "assets.minigamecore.mcap", "catalog.json").toUri());

                if (Files.notExists(path)) {
                    if (Files.notExists(path.getParent())) {
                        Files.createDirectories(path.getParent());
                    }

                    Files.createFile(path);
                }

                ConfigurationLoader<ConfigurationNode> loader = GsonConfigurationLoader.builder().setPath(path).build();
                loader.save(node);
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }

            // TODO do stats
            getMessager().printMessage(NOTE, "=================================");
            getMessager().printMessage(NOTE, "Catalog Stats");
            getMessager().printMessage(NOTE, "=================================");
            getMessager().printMessage(NOTE, format("Rounds: %s, Successes: %s, Failures: %s", rounds, successes, failures));

            if (failures > 0) {
                getMessager().printMessage(NOTE, "^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^");

                if (!notTypes.isEmpty()) {
                    getMessager().printMessage(WARNING, format("%s non Type instances found annotated with @%s:\n",
                            notTypes.size(), CATALOG));

                    notTypes.forEach(element -> getMessager().printMessage(WARNING, element.toString() + "\n"));
                }

                if (!notSpecificTypes.isEmpty()) {
                    getMessager().printMessage(WARNING, format("%s non %s instances found annotated with @%s:\n",
                            notSpecificTypes.size(), CATALOG_TYPE, CATALOG));

                    notSpecificTypes.forEach((element, string) -> getMessager().printMessage(WARNING, format("Attempted %s for %s\n",
                            element.toString(), string)));
                }

                if (!duplicates.isEmpty()) {
                    getMessager().printMessage(WARNING, format("%s duplicates found:\n", duplicates.size()));

                    duplicates.forEach(((element, string) -> {
                        getMessager().printMessage(WARNING, format("Attempted %s for %s\n", element.toString(), string));
                    }));
                }

                getMessager().printMessage(NOTE, "=================================");
            }

            return true;
        }

        rounds++;
        ConfigurationNode localNode = node.getNode("mappings");

        annotations.forEach(annotation -> env.getElementsAnnotatedWith(annotation).forEach(element -> {

            // Only types should have the annotation.
            // Although @Catalog should not compile, if we place it anywhere
            // else, this is just for future proofing.
            if (!(element instanceof TypeElement)) {
                notTypes.add(element);
                failures++;
                return;
            }

            TypeElement typeElement = (TypeElement) element;

            final boolean[] isCatalog = {false};
            final Catalog catalog = typeElement.getAnnotation(Catalog.class);

            // Ensure the Catalog's specified CatalogType is same the element's type
            typeElement.getInterfaces().forEach(iface -> {
                if (!isCatalog[0] && catalog.catalogTypeClass().equals(iface.toString())) {
                    isCatalog[0] = true;
                }
            });

            if (!isCatalog[0]) {
                notSpecificTypes.put(typeElement, catalog.catalogTypeClass());
                failures++;
                return;
            }

            ConfigurationNode tmpNode = localNode.getNode(catalog.catalogTypeClass(), catalog.containerClass(), catalog.field());

            // Check if a mapping exists for a field.
            // A field can only have one value assigned.
            // First to arrive, first to get assigned.
            if (!tmpNode.isVirtual()) {
                duplicates.put(typeElement, format("%s#%s: %s >>>>> Assigned value: %s", catalog.containerClass(), catalog.field(),
                        catalog.catalogTypeClass(), tmpNode.getString()));
                failures++;
                return;
            }

            tmpNode.setValue(typeElement.toString());
            successes++;
        }));

        return true;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return latestSupported();
    }

    private Messager getMessager() {
        return this.processingEnv.getMessager();
    }

}
