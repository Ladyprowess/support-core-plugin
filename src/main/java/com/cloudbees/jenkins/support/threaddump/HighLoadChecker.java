/*
 * The MIT License
 *
 * Copyright 2020 CloudBees, Inc
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

package com.cloudbees.jenkins.support.threaddump;

import com.cloudbees.jenkins.support.impl.ThreadDumps;
import com.cloudbees.jenkins.support.timer.FileListCap;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricSet;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.PeriodicWork;
import jenkins.metrics.api.MetricProvider;
import jenkins.metrics.impl.VMMetricProviderImpl;
import jenkins.model.Jenkins;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static java.util.logging.Level.WARNING;

/**
 * PeriodicWork to check when there is a high load in the instance.
 *
 * Only checking high CPU usage for the moment, but it can be used
 * to generate thread dumps in high heap memory consumption.
 */
@Extension
public class HighLoadChecker extends PeriodicWork {

    /**
     * Recurrence period to check high load consumption and take the corresponded actions
     * like generating a thread dump
     */
    public static final int RECURRENCE_PERIOD_SEC =
            Integer.getInteger(HighLoadChecker.class.getName()+ ".RECURRENCE_PERIOD_SEC", 900);

    /**
     * This is the CPU usage threshold. Determinate de percentage of the total CPU used across all the
     * cores available
     */
    public static final Double CPU_USAGE_THRESHOLD =
            new Double(System.getProperty(HighLoadChecker.class.getName() + ".CPU_USAGE_THRESHOLD", "0.95"));

    /**
     * Limit the number of thread dumps to retain on high cpu
     */
    public static final int HIGH_CPU_THEAD_DUMPS_TO_RETAIN =
            Integer.getInteger(HighLoadChecker.class.getName()+ ".HIGH_CPU_THEAD_DUMPS_TO_RETAIN", 10);

    /**
     * Thread dumps generated on high CPU load are stored in $JENKINS_HOME/high-load/cpu
     **/
    final FileListCap logs = new FileListCap(new File(Jenkins.getInstance().getRootDir(),"high-load/cpu"), HIGH_CPU_THEAD_DUMPS_TO_RETAIN);

    final SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss.SSS");
    {
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    @Override
    public long getRecurrencePeriod() {
        return TimeUnit.SECONDS.toMillis(RECURRENCE_PERIOD_SEC);
    }

    @Override
    protected void doRun() throws Exception {
        Double cpuLoad = null;
        try {
            VMMetricProviderImpl vmMetricProvider = ExtensionList.lookup(MetricProvider.class).get(VMMetricProviderImpl.class);
            MetricSet vmProviderMetricSet = vmMetricProvider.getMetricSet();
            Gauge cpu = (Gauge) vmProviderMetricSet.getMetrics().get("vm.cpu.load");
            cpuLoad = new Double(cpu.getValue().toString());
        } catch (NullPointerException nullPointerException) {
            LOGGER.log(WARNING, "Support Core plugin can't generate thread dumps. Metrics plugin does not seem to be available", nullPointerException);
            return;
        }

        if (Double.compare(Runtime.getRuntime().availableProcessors() * CPU_USAGE_THRESHOLD, cpuLoad) < 0) {
            File threadDumpFile = logs.file(format.format(new Date()) + ".txt");
            try (FileOutputStream fileOutputStream = new FileOutputStream(threadDumpFile)){
                ThreadDumps.threadDump(fileOutputStream);
                logs.add(threadDumpFile);
            }
        }
    }

    private static final Logger LOGGER = Logger
            .getLogger(HighLoadChecker.class.getName());
}
