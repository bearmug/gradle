/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.nativeplatform.toolchain.internal;

import com.google.common.collect.Lists;
import org.gradle.api.Transformer;
import org.gradle.api.internal.tasks.SimpleWorkResult;
import org.gradle.api.internal.tasks.options.Option;
import org.gradle.internal.FileUtils;
import org.gradle.internal.concurrent.DefaultExecutorFactory;
import org.gradle.internal.concurrent.StoppableExecutor;
import org.gradle.language.base.internal.compile.Compiler;
import org.gradle.api.tasks.WorkResult;
import org.gradle.internal.os.OperatingSystem;
import org.gradle.nativeplatform.internal.CompilerOutputFileNamingScheme;

import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;

abstract public class NativeCompiler<T extends NativeCompileSpec> implements Compiler<T> {

    private static final String NATIVE_PARALLEL_TOGGLE = "org.gradle.parallel.native";

    private final CommandLineTool commandLineTool;
    private final ArgsTransformer<T> argsTransformer;
    private final Transformer<T, T> specTransformer;
    private final CommandLineToolInvocation baseInvocation;
    private final String objectFileSuffix;
    private final boolean useCommandFile;

    public NativeCompiler(CommandLineTool commandLineTool, CommandLineToolInvocation baseInvocation, ArgsTransformer<T> argsTransformer, Transformer<T, T> specTransformer, String objectFileSuffix, boolean useCommandFile) {
        this.baseInvocation = baseInvocation;
        this.objectFileSuffix = objectFileSuffix;
        this.useCommandFile = useCommandFile;
        this.argsTransformer = argsTransformer;
        this.specTransformer = specTransformer;
        this.commandLineTool = commandLineTool;
    }

    private boolean useParallelCompile() {
        // TODO: This needs to be fed from command line settings
        return Boolean.getBoolean(NATIVE_PARALLEL_TOGGLE);
    }

    public WorkResult execute(T spec) {
        final T transformedSpec = specTransformer.transform(spec);
        final List<String> genericArgs = getArguments(transformedSpec);
        final StoppableExecutor executor = getExecutor();

        for (File sourceFile : transformedSpec.getSourceFiles()) {
            List<String> perFileArgs = Lists.newArrayList(genericArgs);
            addSourceArgs(perFileArgs, sourceFile);
            addOutputArgs(perFileArgs, getOutputFileDir(sourceFile, transformedSpec.getObjectFileDir()));

            MutableCommandLineToolInvocation perFileInvocation = baseInvocation.copy();
            perFileInvocation.clearPostArgsActions();
            perFileInvocation.setWorkDirectory(transformedSpec.getObjectFileDir());
            perFileInvocation.setArgs(perFileArgs);
            executor.execute(commandLineTool.toRunnableExecution(perFileInvocation));
        }

        // Wait on all executions to complete and clean-up executor
        executor.stop();

        return new SimpleWorkResult(!transformedSpec.getSourceFiles().isEmpty());
    }

    private StoppableExecutor getExecutor() {
        final StoppableExecutor executor;
        if (useParallelCompile()) {
            // TODO: This needs to limit # of threads
            executor = new DefaultExecutorFactory().create(commandLineTool.getDisplayName());
        } else {
            // Single threaded build
            executor = new CallingThreadExecutor();
        }
        return executor;
    }

    private List<String> getArguments(T spec) {
        // TODO: Detangle post args actions from invocation?
        MutableCommandLineToolInvocation postArgsInvocation = baseInvocation.copy();
        postArgsInvocation.setArgs(argsTransformer.transform(spec));
        // NOTE: this triggers the "post args" actions that can modify the arguments
        List<String> genericArgs = postArgsInvocation.getArgs();

        if (useCommandFile) {
            OptionsFileArgsWriter writer = optionsFileTransformer(spec.getTempDir());
            // Shorten args and write out an options.txt file
            genericArgs = writer.transform(genericArgs);
        }
        return genericArgs;
    }

    protected void addSourceArgs(List<String> args, File sourceFile) {
        args.add(sourceFile.getAbsolutePath());
    }

    protected abstract void addOutputArgs(List<String> args, File outputFile);

    protected abstract OptionsFileArgsWriter optionsFileTransformer(File tempDir);

    private File getOutputFileDir(File sourceFile, File objectFileDir) {
        boolean windowsPathLimitation = OperatingSystem.current().isWindows();

        File outputFile = new CompilerOutputFileNamingScheme()
                .withObjectFileNameSuffix(objectFileSuffix)
                .withOutputBaseFolder(objectFileDir)
                .map(sourceFile);
        File outputDirectory = outputFile.getParentFile();
        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs();
        }
        return windowsPathLimitation ? FileUtils.assertInWindowsPathLengthLimitation(outputFile) : outputFile;
    }

    /**
     * Re-uses calling thread for execute() call
     */
    private static class CallingThreadExecutor implements StoppableExecutor {
        @Override
        public void stop() { }

        @Override
        public void stop(int timeoutValue, TimeUnit timeoutUnits) throws IllegalStateException { }

        @Override
        public void requestStop() { }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }
}