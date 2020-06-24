/*
 * Copyright (C) 2012 CloudBees Inc.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * along with this program; if not, see <http://www.gnu.org/licenses/>.
 */

package com.cloudbees.plugins;

import hudson.Extension;
import hudson.FilePath;
import hudson.model.*;
import hudson.model.Queue;
import hudson.model.queue.CauseOfBlockage;
import hudson.tasks.BatchFile;
import hudson.tasks.CommandInterpreter;
import hudson.tasks.Shell;
import hudson.util.ListBoxModel;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static hudson.model.TaskListener.NULL;

/**
 * @author: <a hef="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class JobPrerequisites extends JobProperty<AbstractProject<?, ?>> implements Action {
    private transient static final Logger LOGGER = Logger.getLogger(JobPrerequisites.class.getName());

    private final String script;
    private final String interpreter;
    private static final String MARKER    = "#:#:#";
    private static final String CAUSE_VAR = "CAUSE";
    private static final String CRLF      = "\r\n";
    public static final String SHELL_SCRIPT = "linux shell script";
    public static final String WINDOWS = "windows batch script";

    @DataBoundConstructor
    public JobPrerequisites(String script, String interpreter) {
        this.script = script;
        this.interpreter = interpreter;
    }

    public String getScript() {
        return script;
    }

    /**
     * @return true if all prerequisites a met on the target Node
     */
    public CauseOfBlockage check(Node node, Queue.BuildableItem item) throws InterruptedException {
        FilePath root = node.getRootPath();
        if (root == null) return new CauseOfBlockage.BecauseNodeIsOffline(node); //offline ?

        HashMap<String, String> envs = new HashMap<>();
        List<ParametersAction> actions = item.getActions(ParametersAction.class);
        for (ParametersAction action : actions) {
            List<ParameterValue> parameters = action.getParameters();
            for (ParameterValue parameter : parameters) {
                if(parameter instanceof StringParameterValue){
                    StringParameterValue p = (StringParameterValue) parameter;
                    envs.put(p.getName(), p.getValue());
                }else if(parameter instanceof BooleanParameterValue){
                    BooleanParameterValue p = (BooleanParameterValue) parameter;
                    envs.put(p.getName(), p.getValue() + "");
                }
            }
        }

        try {
            FilePath scriptFile =  createScriptFile(root);
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                String[] cmd = buildCommandLine(scriptFile);
                int exitCode = node.createLauncher(NULL)
                        .launch()
                        .cmds(cmd)
                        .envs(envs)
                        .stdout(baos)
                        .pwd(root)
                        .start()
                        .joinWithTimeout(60, TimeUnit.SECONDS, NULL);
                String output = baos.toString();
                LOGGER.log(Level.WARNING, "command execution output:\n" + output);
                if (exitCode == 0) {
                    return null;
                }
                LOGGER.log(Level.WARNING, "command execution exit not zero");
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "command execution failed", e);
            } finally {
                try {
                    scriptFile.delete();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Unable to delete script file", e);
                }            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Unable to produce a script file", e);
        }
        LOGGER.log(Level.WARNING, "command execution exit not zero then return BecausePrerequisitesArentMet");

        return new BecausePrerequisitesArentMet(node);

    }

    private String[] buildCommandLine(FilePath batchFile) {
        String[] cmd;
        if (isUnix()) {
            cmd = new String[]{"bash", batchFile.getRemote()};
        } else {
            cmd = new String[]{"cmd", "/c", "call", batchFile.getRemote()};
        }
        return cmd;
    }

    private FilePath createScriptFile(@Nonnull FilePath dir) throws IOException, InterruptedException {
        return dir.createTextTempFile("jenkins", getFileExtension(), getContents(), false);
    }

    private String getFileExtension() {
        if (isUnix()) {
            return ".sh";
        } else {
            return ".bat";
        }
    }

    private String getContents() {
        String contents = ""
                + "@set "+ CAUSE_VAR +"=" +CRLF
                + "@echo off" + CRLF
                + "call :TheActualScript" + CRLF
                + "@echo off" + CRLF
                + "echo " + MARKER + CAUSE_VAR + MARKER + "%" + CAUSE_VAR + "%" + MARKER + CRLF
                + "goto :EOF" + CRLF
                + ":TheActualScript" + CRLF
                + script + CRLF;
        if (isUnix()) {
            return script;
        }else {
            return contents;
        }
    }

    private boolean isUnix() {
        return File.pathSeparatorChar == ':';
    }
    @Extension
    public final static class DescriptorImpl extends JobPropertyDescriptor {

        @Override
        public String getDisplayName() {
            return "Check prerequisites before job can build on a slave node";
        }

        @Override
        public boolean isApplicable(Class<? extends Job> jobType) {
            return AbstractProject.class.isAssignableFrom(jobType);
        }

        @Override
        public JobProperty<?> newInstance(StaplerRequest req, JSONObject formData) throws FormException {
            if (formData.isNullObject()) {
                return null;
            }
            JSONObject prerequisites = formData.getJSONObject("prerequisites");
            if (prerequisites.isNullObject()) {
                return null;
            }
            return req.bindJSON(JobPrerequisites.class, prerequisites);
        }

        public ListBoxModel doFillInterpreterItems() {
            return new ListBoxModel()
                    .add(SHELL_SCRIPT)
                    .add(WINDOWS);
        }

    }

    // fake implementations for Action, required to contribute the job configuration UI

    public String getDisplayName() {
        return null;
    }

    public String getIconFileName() {
        return null;
    }

    public String getUrlName() {
        return null;
    }

}
