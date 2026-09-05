package ru.milkyway.plugmanreloaded.update.input;

import ru.milkyway.plugmanreloaded.utils.TaskScheduler;

public class ManualSourceSession {

    private final String pluginName;
    private final String mainClass;
    private Object timeoutTask;
    private volatile long lastInputTime = 0L;

    public ManualSourceSession(String pluginName, String mainClass, Object timeoutTask) {
        this.pluginName = pluginName;
        this.mainClass = mainClass;
        this.timeoutTask = timeoutTask;
    }

    public boolean tryConsumeInput() {
        long now = System.currentTimeMillis();
        if (now - lastInputTime < 500L) {
            return false;
        }
        lastInputTime = now;
        return true;
    }

    public String getPluginName() {
        return pluginName;
    }

    public String getMainClass() {
        return mainClass;
    }

    public void setTimeoutTask(Object timeoutTask) {
        cancelTimeout();
        this.timeoutTask = timeoutTask;
    }

    public void cancelTimeout() {
        if (timeoutTask != null) {
            TaskScheduler.cancelTask(timeoutTask);
            timeoutTask = null;
        }
    }
}

