/*
 * The MIT License
 *
 * Copyright 2014-2015 TweetWallFX
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
package org.tweetwallfx.controls.stepengine;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import javafx.application.Platform;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

/**
 *
 * @author Jörg Michelberger
 */
public final class StepEngine {

    private static final Logger LOG = LogManager.getLogger(StepEngine.class.getName());
    private volatile boolean terminated = false;

    private final Lock lock = new ReentrantLock();
    private final Condition condition = lock.newCondition();

    private final StepIterator stateIterator;

    private final MachineContext context = new MachineContext();

    public StepEngine(StepIterator stateIterator) {
        this.stateIterator = stateIterator;
        //initialize every step with context
        stateIterator.applyWith((step) -> step.initStep(context));
    }

    public MachineContext getContext() {
        return context;
    }

    public final class MachineContext {

        private final Map<String, Object> properties = new HashMap<>();

        public Object get(String key) {
            return properties.get(key);
        }

        public Object put(String key, Object value) {
            return properties.put(key, value);
        }

        public void proceed() {
            StepEngine.this.proceed();
        }
    }

    public void go() {
        process();
    }

    void proceed() {
        lock.lock();
        try {
            condition.signalAll();
        } finally {
            lock.unlock();
        }
    }

    void process() {
        while (!terminated) {
            LOG.info("process to next step ");

            long start = System.currentTimeMillis();

            Step step = stateIterator.next();
            while (step.shouldSkip(context)) {
                LOG.info("Skip step: " + step.getName());
                step = stateIterator.next();
            }
            final Step stepToExecute = step;
            long duration = step.preferredStepDuration(context);
            LOG.info("call " + stepToExecute.getName() + "doStep()");
            lock.lock();
            try {
                if (stepToExecute.requiresPlatformThread()) {
                    Platform.runLater(() -> stepToExecute.doStep(context));                    
                } else {
                    stepToExecute.doStep(context);
                }
                long stop = System.currentTimeMillis();
                long doStateDuration = stop - start;
                long delay = duration - doStateDuration;
                if (delay > 0) {
                    try {
                        LOG.info("sleep(" + delay + ")");
                        Thread.sleep(delay);
                    } catch (InterruptedException ex) {
                        LOG.error("Sleeping for " + delay + " interrupted!", ex);
                    }
                }
                LOG.info("wait for the step to finish!");
                condition.await();
            } catch (InterruptedException ex) {
                LOG.error("Waiting interrupted!", ex);
            } finally {
                lock.unlock();
            }
        }
    }
}
