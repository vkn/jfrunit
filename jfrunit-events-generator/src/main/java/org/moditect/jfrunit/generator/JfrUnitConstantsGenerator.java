/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2020 - 2021 The JfrUnit authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.moditect.jfrunit.generator;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;
import org.moditect.jfrunit.generator.events.model.Event;
import org.moditect.jfrunit.generator.events.model.JfrDoc;
import org.moditect.jfrunit.generator.events.model.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.JavaFileObject;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.Writer;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JFR types generator; given a JSON file that represents the standard JFR events,
 * it generates classes and default methods that can be used with JfrUnit in order
 * to build assertions.
 */
public class JfrUnitConstantsGenerator {

    static List<String> baseTypes = List.of("boolean", "byte", "char", "double", "float", "int", "long", "short", "String");
    static ObjectMapper MAPPER = new ObjectMapper();
    static Logger LOGGER = LoggerFactory.getLogger(JfrUnitConstantsGenerator.class);
    static String PACKAGE = "org.moditect.jfrunit.events";
    static String BASE_FOLDER_GEN = "src/main/java/";
    private static Configuration cfg = new Configuration(Configuration.VERSION_2_3_31);

    public static void generate(String jfrDocUrl, ProcessingEnvironment processingEnvironment) throws IOException, TemplateException {
        String docUrl = jfrDocUrl;
        if (docUrl == null || docUrl.isEmpty() || "${jrfDocUrl}".equals(docUrl)) {
            var dir = System.getProperty("user.dir");
            docUrl = "file://" + dir + "/jfrunit-events-generator/src/main/resources/jdk21-events.json";
            LOGGER.info("Using docs from {}", docUrl);
        }
        JfrDoc jrfDoc = MAPPER.readValue(new BufferedInputStream(new URL(docUrl).openStream()), JfrDoc.class);

        LOGGER.info("generating sources for version {} and distribution {}", jrfDoc.getVersion(), jrfDoc.getDistribution());

        configureFremarker();

        generateEventClasses(processingEnvironment, jrfDoc);

        generateJfrEventTypes(processingEnvironment, jrfDoc);

        generateTypeClasses(processingEnvironment, jrfDoc);

        LOGGER.info("sources generated in {} folder", BASE_FOLDER_GEN);
    }

    /**
     * Generate types that model the events, these classes are located into generated-sources, org.moditect.jfrunit.events.model package.
     *
     * @param processingEnvironment
     * @param jrfDoc
     * @throws IOException
     * @throws TemplateException
     * @see org.moditect.jfrunit.events.model.Bytecode;
     * @see org.moditect.jfrunit.events.model.GCCause;
     */
    private static void generateTypeClasses(ProcessingEnvironment processingEnvironment, JfrDoc jrfDoc) throws IOException, TemplateException {
        Template freemarkerTemplate;
        JavaFileObject builderFile;
        freemarkerTemplate = cfg.getTemplate("type.ftlh");
        for (Type type : jrfDoc.getTypes()) {
            if (!baseTypes.contains(type.getName())) {
                builderFile = processingEnvironment.getFiler().createSourceFile(PACKAGE + ".model." + type.getName());
                try (Writer out = builderFile.openWriter()) {
                    Map<String, Object> root = new HashMap();
                    root.put("package", PACKAGE);
                    root.put("type", type);

                    freemarkerTemplate.process(root, out);
                }
            }
        }
    }

    /**
     * Generate JfrEventTypes.java file that contains all the event instances.
     *
     * @param processingEnvironment
     * @param jrfDoc
     * @throws IOException
     * @throws TemplateException
     * @see org.moditect.jfrunit.events.JfrEventTypes
     */
    private static void generateJfrEventTypes(ProcessingEnvironment processingEnvironment, JfrDoc jrfDoc) throws IOException, TemplateException {
        Template freemarkerTemplate;
        freemarkerTemplate = cfg.getTemplate("event-types.ftlh");
        JavaFileObject builderFile = processingEnvironment.getFiler().createSourceFile(PACKAGE + ".JfrEventTypes");
        try (Writer out = builderFile.openWriter()) {
            Map<String, Object> root = new HashMap();
            root.put("package", PACKAGE);
            root.put("events", jrfDoc.getEvents());

            freemarkerTemplate.process(root, out);
        }
    }

    /**
     * Generate the events fired by the jdk, these classes are located into generated-sources, org.moditect.jfrunit.events package.
     *
     * <p>
     *   TODO there are events (few of them actually) like org.moditect.jfrunit.events.ModuleExport that uses types generated by generateTypeClasses(...)
     * 	 right now it is not possible to assert on those
     * </p>
     *
     * @param processingEnvironment
     * @param jrfDoc
     * @throws IOException
     * @throws TemplateException
     * @see org.moditect.jfrunit.events.ThreadSleep
     */
    private static void generateEventClasses(ProcessingEnvironment processingEnvironment, JfrDoc jrfDoc) throws IOException, TemplateException {
        Template freemarkerTemplate = cfg.getTemplate("jdk-event.ftlh");
        for (Event event : jrfDoc.getEvents()) {
            JavaFileObject builderFile = processingEnvironment.getFiler().createSourceFile(PACKAGE + "." + event.getName());
            try (Writer out = builderFile.openWriter()) {
                Map<String, Object> root = new HashMap();
                root.put("package", PACKAGE);
                root.put("event", event);

                freemarkerTemplate.process(root, out);
            }
        }
    }

    private static void configureFremarker() {
        cfg.setClassForTemplateLoading(JfrUnitConstantsGenerator.class, "/templates");
        // Recommended settings for new projects:
        cfg.setDefaultEncoding("UTF-8");
        cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        cfg.setLogTemplateExceptions(false);
        cfg.setWrapUncheckedExceptions(true);
        cfg.setFallbackOnNullLoopVariable(false);
    }

    static {
        MAPPER.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public static String pascalCaseToSnakeCase(String pascalName) {
        if (pascalName == null) {
            return null;
        }

        StringBuilder result = new StringBuilder();
        char[] charArray = pascalName.toCharArray();
        for (int i = 0; i < charArray.length; i++) {
            Character c = pascalName.charAt(i);

            int nextCharPos = i + 1;

            if (i > 0 && nextCharPos < charArray.length) {
                char previous = pascalName.charAt(i - 1);
                char next = pascalName.charAt(nextCharPos);

                if ((Character.isLowerCase(c) && Character.isUpperCase(next))
                        || (!Character.isLetter(c) && Character.isLetter(next))) {
                    result.append(c.toString().toUpperCase());
                    result.append("_");
                }
                else if (Character.isUpperCase(previous)
                        && Character.isUpperCase(c)
                        && Character.isLowerCase(next)) {
                    result.append("_");
                    result.append(c.toString().toUpperCase());
                }
                else {
                    result.append(c.toString().toUpperCase());
                }
            }
            else {
                result.append(c.toString().toUpperCase());
            }
        }

        return result.toString();
    }
}
