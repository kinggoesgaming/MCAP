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
import static javax.tools.Diagnostic.Kind.ERROR;
import static javax.tools.Diagnostic.Kind.NOTE;
import static javax.tools.Diagnostic.Kind.WARNING;
import static javax.tools.StandardLocation.SOURCE_OUTPUT;
import static ninja.leaping.configurate.commented.SimpleCommentedConfigurationNode.root;

import io.github.minigamecore.mcap.annotate.catalog.Catalog;
import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.gson.GsonConfigurationLoader;
import ninja.leaping.configurate.loader.ConfigurationLoader;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

import javax.annotation.Nonnull;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * The processor to analyze {@link Catalog}.
 */
@SupportedAnnotationTypes({CatalogProcessor.CATALOG})
public class CatalogProcessor extends AbstractProcessor {

    // Types and Annotation Qualified Names
    static final String CATALOG = "io.github.minigamecore.mcap.annotate.catalog.Catalog";
    private static final String CATALOG_TYPE = "org.spongepowered.api.CatalogType";

    // Configurate
    private ConfigurationNode node = root();
    private Filer filer;

    // Statistics
    private int rounds = 0;
    private int assignments = 0;

    // Utils
    private Elements elements;
    private Types types;

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);

        // Configurate
        filer = env.getFiler();
        node.getNode("version").setValue(1);

        // Utils
        elements = env.getElementUtils();
        types = env.getTypeUtils();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {

        if (env.processingOver()) {

            if (!env.errorRaised()) {
                createFile();
                printStats();
            }
            return false;
        }

        rounds++;
        ConfigurationNode localNode = node.getNode("mappings");

        annotations.forEach(annotation -> env.getElementsAnnotatedWith(Catalog.class).forEach(element -> {

            // Only classes should have the annotation.
            // Although @Catalog should not compile if placed on methods,
            // fields, constructors, etc, however interfaces and enums still
            // are legal Types for assignment.
            if (!(element.getKind() == ElementKind.CLASS)) {
                getMessager().printMessage(ERROR, format("@%s can only be placed on classes.", CATALOG), element);
            }

            TypeElement typeElement = (TypeElement) element;

            // Ensure the element implements CatalogType.
            if (!typeElement.asType().equals(elements.getTypeElement(CATALOG_TYPE).asType())) {
                if (!isAssignable(typeElement.asType(), elements.getTypeElement(CATALOG_TYPE).asType())) {
                    getMessager().printMessage(ERROR, format("%s does not implement %s", typeElement, CATALOG_TYPE), typeElement);
                }
            }

            final Catalog catalog = typeElement.getAnnotation(Catalog.class);
            TypeMirror compare = types.erasure(elements.getTypeElement(catalog.catalogTypeClass()).asType());

            // Ensure the Catalog's specified CatalogType is same the element's type
            if (!typeElement.asType().equals(compare)) {

                if (!isAssignable(typeElement.asType(), compare)) {
                    getMessager().printMessage(ERROR, format("%s is not an instance of %s", typeElement, catalog.catalogTypeClass()), typeElement);
                }
            }

            // Check if the target pseudo enum class exists
            final TypeElement containerClassElement = elements.getTypeElement(catalog.containerClass());

            if (containerClassElement == null) {
                getMessager().printMessage(ERROR, format("The catalog container class %s does not exist", catalog.containerClass()), typeElement);
            }

            assert containerClassElement != null;

            List<VariableElement> variableElements = containerClassElement.getEnclosedElements().stream()
                    .filter(elm1 -> elm1.getKind() == ElementKind.FIELD)
                    .map((Function<Element, VariableElement>) element1 -> (VariableElement) element1)
                    .filter(elm -> catalog.field().equals(elm.getSimpleName().toString())).collect(Collectors.toList());

            if (variableElements.size() == 0) {
                getMessager().printMessage(ERROR, format("Field %s does not exist in class %s", catalog.field(), catalog.containerClass()),
                        typeElement);
            }

            variableElements.forEach(elm -> {

                if (!elm.getModifiers().contains(Modifier.PUBLIC)) {
                    getMessager().printMessage(ERROR, format("Field %s in class %s is not public", catalog.field(), catalog.containerClass()), elm);
                }

                if (!elm.getModifiers().contains(Modifier.STATIC)) {
                    getMessager().printMessage(ERROR, format("Field %s in class %s is not static", catalog.field(), catalog.containerClass()), elm);
                }

                if (!elm.getModifiers().contains(Modifier.FINAL)) {
                    getMessager().printMessage(WARNING, format("Field %s in class %s is not final", catalog.field(), catalog.containerClass()), elm);
                }

                if (!types.erasure(compare).toString().equals(types.erasure(elm.asType()).toString())) {

                    if (!isAssignable(types.erasure(elm.asType()), elements.getTypeElement(catalog.catalogTypeClass()).asType())) {
                        getMessager().printMessage(ERROR, format("Field %s in class %s is not of type %s", catalog.field(), catalog.containerClass(),
                                catalog.catalogTypeClass()), typeElement);
                    }
                }
            });

            ConfigurationNode tmpNode = localNode.getNode(catalog.catalogTypeClass(), catalog.containerClass(), catalog.field());

            // Check if a mapping exists for a field.
            // A field can only have one value assigned.
            // First to arrive, first to get assigned.
            if (!tmpNode.isVirtual()) {
                getMessager().printMessage(ERROR,
                        format("Field %s in class %s already has a mapping available", catalog.field(), catalog.containerClass()), typeElement);
            }

            tmpNode.setValue(typeElement.toString());
            assignments++;
        }));

        return false;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return latestSupported();
    }

    private Messager getMessager() {
        return this.processingEnv.getMessager();
    }

    private boolean isAssignable(@Nonnull TypeMirror type, @Nonnull TypeMirror compareType) {
        for (TypeMirror tm : processingEnv.getTypeUtils().directSupertypes(type)) {

            if (types.isAssignable(tm, compareType)) {
                return true;
            }
        }
        return false;
    }

    private void createFile() {
        Path tmpFile = null;
        try {
            tmpFile = Files.createTempFile("catalog", "");
        } catch (IOException e) {
            getMessager().printMessage(ERROR, "Error creating tmp file");
            e.printStackTrace();
        }

        assert tmpFile != null;
        ConfigurationLoader<ConfigurationNode> loader = GsonConfigurationLoader.builder().setPath(tmpFile).build();

        try {
            loader.save(node);
        } catch (IOException e) {
            getMessager().printMessage(ERROR, format("Error saving to tmp file %s", tmpFile));
            e.printStackTrace();
        }

        Path output;

        try {
            output = Paths.get(filer.createResource(SOURCE_OUTPUT, "", "assets/minigamecore/mcap/catalog.json.gz").toUri());

            if (Files.notExists(output)) {

                if (Files.notExists(output.getParent())) {
                    Files.createDirectories(output.getParent());
                }

                Files.createFile(output);
            }
            try (BufferedInputStream istream = new BufferedInputStream(new FileInputStream(tmpFile.toFile()));
                    BufferedOutputStream ostream = new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(output.toFile())))) {
                byte[] buffer = new byte[1024];

                int length;
                while ((length = istream.read(buffer)) > 0) {
                    ostream.write(buffer, 0, length);
                }

                ostream.flush();
            }
        } catch (IOException e) {
            try {
                output = Paths.get(filer.createResource(SOURCE_OUTPUT, "", "assets/minigamecore/mcap/catalog.json").toUri());

                if (Files.notExists(output)) {

                    if (Files.notExists(output.getParent())) {
                        Files.createDirectories(output.getParent());
                    }

                    Files.createFile(output);
                }
                try (BufferedInputStream istream = new BufferedInputStream(new FileInputStream(tmpFile.toFile()));
                        BufferedOutputStream ostream = new BufferedOutputStream(new FileOutputStream(output.toFile()))) {
                    byte[] buffer = new byte[1024];

                    int length;
                    while ((length = istream.read(buffer)) > 0) {
                        ostream.write(buffer, 0, length);
                    }

                    ostream.flush();
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        }


        try {
            Files.deleteIfExists(tmpFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void printStats() {
        getMessager().printMessage(NOTE, "====================================");
        getMessager().printMessage(NOTE, "Catalog Stats");
        getMessager().printMessage(NOTE, "====================================");
        getMessager().printMessage(NOTE, format("Rounds: %s, Assignments: %s", rounds, assignments));
        getMessager().printMessage(NOTE, "====================================");
    }

}
