/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.seatunnel.e2e.common.util;

import org.apache.seatunnel.api.configuration.ReadonlyConfig;
import org.apache.seatunnel.api.table.factory.FactoryException;
import org.apache.seatunnel.e2e.common.container.TestContainer;

import org.apache.seatunnel.shade.com.typesafe.config.Config;
import org.apache.seatunnel.shade.com.typesafe.config.ConfigFactory;
import org.apache.seatunnel.shade.com.typesafe.config.ConfigResolveOptions;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.MountableFile;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Slf4j
public final class ContainerUtil {

    public static final String PLUGIN_MAPPING_FILE = "plugin-mapping.properties";

    /**
     * An error occurs when the user is not a submodule of seatunnel-e2e.
     */
    public static final String PROJECT_ROOT_PATH = Paths.get(System.getProperty("user.dir")).getParent().getParent().getParent().toString();

    public static void copyConnectorJarToContainer(GenericContainer<?> container,
                                                   String confFile,
                                                   String connectorsRootPath,
                                                   String connectorPrefix,
                                                   String connectorType,
                                                   String seatunnelHome) {
        Config jobConfig = getConfig(getConfigFile(confFile));
        Config connectorsMapping = getConfig(new File(PROJECT_ROOT_PATH + File.separator + PLUGIN_MAPPING_FILE));
        if (!connectorsMapping.hasPath(connectorType) || connectorsMapping.getConfig(connectorType).isEmpty()) {
            return;
        }
        Config connectors = connectorsMapping.getConfig(connectorType);
        Set<String> connectorNames = getConnectors(jobConfig, connectors, "source");
        connectorNames.addAll(getConnectors(jobConfig, connectors, "sink"));
        File module = new File(PROJECT_ROOT_PATH + File.separator + connectorsRootPath);

        List<File> connectorFiles = getConnectorFiles(module, connectorNames, connectorPrefix);
        connectorFiles.forEach(jar ->
            container.copyFileToContainer(
                MountableFile.forHostPath(jar.getAbsolutePath()),
                Paths.get(Paths.get(seatunnelHome, "connectors").toString(), connectorType, jar.getName()).toString()));
    }

    public static String copyConfigFileToContainer(GenericContainer<?> container, String confFile) {
        final String targetConfInContainer = Paths.get("/tmp", confFile).toString();
        container.copyFileToContainer(MountableFile.forHostPath(getConfigFile(confFile).getAbsolutePath()), targetConfInContainer);
        return targetConfInContainer;
    }

    public static void copySeaTunnelStarter(GenericContainer<?> container,
                                            String startModuleName,
                                            String startModulePath,
                                            String seatunnelHomeInContainer,
                                            String startShellName) {
        final String startJarName = startModuleName + ".jar";
        // copy lib
        final String startJarPath = startModulePath + File.separator + "target" + File.separator + startJarName;
        checkPathExist(startJarPath);
        container.copyFileToContainer(
            MountableFile.forHostPath(startJarPath),
            Paths.get(Paths.get(seatunnelHomeInContainer, "lib").toString(), startJarName).toString());

        // copy bin
        final String startBinPath = startModulePath + File.separator + "src/main/bin/" + startShellName;
        checkPathExist(startBinPath);
        container.copyFileToContainer(
            MountableFile.forHostPath(startBinPath),
            Paths.get(Paths.get(seatunnelHomeInContainer, "bin").toString(), startShellName).toString());

        // copy plugin-mapping.properties
        container.copyFileToContainer(
            MountableFile.forHostPath(PROJECT_ROOT_PATH + "/plugin-mapping.properties"),
            Paths.get(Paths.get(seatunnelHomeInContainer, "connectors").toString(), PLUGIN_MAPPING_FILE).toString());
    }

    public static String adaptPathForWin(String path) {
        // Running IT use cases under Windows requires replacing \ with /
        return path == null ? "" : path.replaceAll("\\\\", "/");
    }

    private static List<File> getConnectorFiles(File currentModule, Set<String> connectorNames, String connectorPrefix) {
        List<File> connectorFiles = new ArrayList<>();
        for (File file : Objects.requireNonNull(currentModule.listFiles())) {
            getConnectorFiles(file, connectorNames, connectorPrefix, connectorFiles);
        }
        return connectorFiles;
    }

    private static void getConnectorFiles(File currentModule, Set<String> connectorNames, String connectorPrefix, List<File> connectors) {
        if (currentModule.isFile() || connectorNames.size() == connectors.size()) {
            return;
        }
        if (connectorNames.contains(currentModule.getName())) {
            File targetPath = new File(currentModule.getAbsolutePath() + File.separator + "target");
            for (File file : Objects.requireNonNull(targetPath.listFiles())) {
                if (file.getName().startsWith(currentModule.getName()) && !file.getName().endsWith("javadoc.jar")) {
                    connectors.add(file);
                    return;
                }
            }
        }

        if (currentModule.getName().startsWith(connectorPrefix)) {
            for (File file : Objects.requireNonNull(currentModule.listFiles())) {
                getConnectorFiles(file, connectorNames, connectorPrefix, connectors);
            }
        }
    }

    private static Set<String> getConnectors(Config jobConfig, Config connectorsMap, String pluginType) {
        List<? extends Config> connectorConfigList = jobConfig.getConfigList(pluginType);
        Map<String, String> connectors = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        ReadonlyConfig.fromConfig(connectorsMap.getConfig(pluginType)).toMap(connectors);
        return connectorConfigList.stream()
            .map(config -> config.getString("plugin_name"))
            .filter(connectors::containsKey)
            .map(connectors::get)
            .collect(Collectors.toSet());
    }

    public static Path getCurrentModulePath() {
        return Paths.get(System.getProperty("user.dir"));
    }

    private static File getConfigFile(String confFile) {
        File file = new File(getCurrentModulePath() + "/src/test/resources" + confFile);
        if (file.exists()) {
            return file;
        }
        throw new IllegalArgumentException(confFile + " doesn't exist");
    }

    private static Config getConfig(File file) {
        return ConfigFactory
            .parseFile(file)
            .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true))
            .resolveWith(ConfigFactory.systemProperties(), ConfigResolveOptions.defaults().setAllowUnresolved(true));
    }

    public static void checkPathExist(String path) {
        Assertions.assertTrue(new File(path).exists(), path + " must exist");
    }

    public static List<TestContainer> discoverTestContainers() {
        try {
            final List<TestContainer> result = new LinkedList<>();
            ServiceLoader.load(TestContainer.class, Thread.currentThread().getContextClassLoader())
                .iterator()
                .forEachRemaining(result::add);
            return result;
        } catch (ServiceConfigurationError e) {
            log.error("Could not load service provider for containers.", e);
            throw new FactoryException("Could not load service provider for containers.", e);
        }
    }
}
