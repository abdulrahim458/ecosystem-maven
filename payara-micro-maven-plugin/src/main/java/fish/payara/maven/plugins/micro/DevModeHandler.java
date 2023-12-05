/*
 *
 * Copyright (c) 2023 Payara Foundation and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://github.com/payara/Payara/blob/master/LICENSE.txt
 * See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * The Payara Foundation designates this particular file as subject to the "Classpath"
 * exception as provided by the Payara Foundation in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package fish.payara.maven.plugins.micro;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import static java.util.Arrays.asList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.invoker.MavenInvocationException;

/**
 *
 * @author Gaurav Gupta
 */
public class DevModeHandler implements Runnable {

    private final MavenProject project;
    private final Log log;
    private final ExecutorService executorService;
    private final List<String> devModeGoals;
    private WatchService watchService;
    private Future<?> buildReloadTask;
    private final AtomicBoolean cleanPending = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, Boolean> sourceUpdatedPending = new ConcurrentHashMap<>();
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    public DevModeHandler(MavenProject project, Log log, String devModeGoals) {
        this.project = project;
        this.log = log;
        this.devModeGoals = asList(devModeGoals.split("\\s+"));
        this.executorService = Executors.newSingleThreadExecutor();
    }

    public void stop() {
        stopRequested.set(true);
    }

    @Override
    public void run() {
        try {
            Path sourcePath = Paths.get(project.getBasedir() + File.separator + "src");
            this.watchService = FileSystems.getDefault().newWatchService();
            sourcePath.register(watchService,
                    ENTRY_CREATE,
                    ENTRY_DELETE,
                    ENTRY_MODIFY);

            registerAllDirectories(sourcePath);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    buildReloadTask.cancel(true);
                    executorService.shutdown();
                } catch (Exception ex) {
                    log.error(ex);
                }
            }));

            while (!stopRequested.get()) {
                WatchKey key = watchService.poll(60, TimeUnit.SECONDS);
                if (key != null) {
                    if (buildReloadTask != null && !buildReloadTask.isDone()) {
                        buildReloadTask.cancel(true);
                    }
                    boolean fileDeletedOrRenamed = false;
                    boolean resourceModified = false;
                    boolean testClassesModified = false;
                    List<String> goalsList = new ArrayList<>(devModeGoals);
                    for (WatchEvent<?> event : key.pollEvents()) {
                        WatchEvent.Kind<?> kind = event.kind();
                        Path changed = (Path) event.context();
                        log.info("Source modified: " + changed + " - " + kind);
                        sourceUpdatedPending.put(changed + "-" + kind, true);
                        Path fullPath = sourcePath.resolve(changed);
                        Path projectRoot = Paths.get(project.getBasedir().toURI());
                        Path resourcesDirectory = projectRoot.resolve("src").resolve("main").resolve("resources");
                        Path testDirectory = projectRoot.resolve("src").resolve("test");

                        if (fullPath.startsWith(resourcesDirectory)) {
                            resourceModified = true;
                        }
                        if (fullPath.startsWith(testDirectory)) {
                            testClassesModified = true;
                        }
                        if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                            if (Files.isDirectory(fullPath, LinkOption.NOFOLLOW_LINKS)) {
                                registerDirectory(fullPath);
                            }
                        }
                        if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                            fileDeletedOrRenamed = true;
                            cleanPending.set(true);
                        }
                    }

                    updateGoalsList(goalsList, fileDeletedOrRenamed, resourceModified, testClassesModified);

                    executeBuildReloadTask(goalsList);
                    key.reset();
                }
            }
        } catch (IOException | InterruptedException ex) {
            log.error(ex);
        }
    }

    private void registerAllDirectories(Path path) throws IOException {
        Files.walk(path)
                .filter(Files::isDirectory)
                .forEach(this::registerDirectory);
    }

    private void registerDirectory(Path dir) {
        try {
            dir.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
        } catch (IOException ex) {
            log.error("Error registering directories", ex);
        }
    }

    private void updateGoalsList(List<String> goalsList, boolean fileDeletedOrRenamed, boolean resourceModified,
            boolean testClassesModified) {
        if (fileDeletedOrRenamed || cleanPending.get() || sourceUpdatedPending.size() > 1) {
            goalsList.add(0, "clean");
        } else if (!resourceModified) {
            goalsList.add("-Dmaven.resources.skip=true");
        }
        if (!testClassesModified) {
            goalsList.add("-Dmaven.test.skip=true");
        } else {
            goalsList.add("-DskipTests");
        }
    }

    private void executeBuildReloadTask(List<String> goalsList) {
        buildReloadTask = executorService.submit(() -> {
            InvocationRequest request = new DefaultInvocationRequest();
            request.setPomFile(new File(project.getBasedir(), "pom.xml"));
            request.setGoals(goalsList);
            log.info("Maven goals: " + goalsList);
            System.setProperty("maven.multiModuleProjectDirectory", project.getBasedir().toString());

            Invoker invoker = new DefaultInvoker();
            invoker.setLogger(new InvokerLoggerImpl(log));
            invoker.setInputStream(InputStream.nullInputStream());
            try {
                InvocationResult result = invoker.execute(request);
                if (result.getExitCode() != 0) {
                    log.debug("Auto-build failed with exit code: " + result.getExitCode());
                } else {
                    log.info(project.getName() + " auto-build successful");
                    cleanPending.set(false);
                    sourceUpdatedPending.clear();
                    ReloadMojo reloadMojo = new ReloadMojo(project, log);
                    try {
                        reloadMojo.execute();
                    } catch (MojoExecutionException ex) {
                        log.error("Error invoking Reload", ex);
                    }
                }
            } catch (MavenInvocationException ex) {
                log.error("Error invoking Maven", ex);
            }
        });
    }
}
