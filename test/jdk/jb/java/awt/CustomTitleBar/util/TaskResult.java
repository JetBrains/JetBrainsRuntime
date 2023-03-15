package util;

public class TaskResult {

    private final boolean passed;
    private final boolean metConditions;
    private final String error;

    public TaskResult(boolean passed, String error) {
        this.passed = passed;
        this.metConditions = true;
        this.error = error;
    }

    public TaskResult(boolean metConditions, boolean passed, String error) {
        this.metConditions = metConditions;
        this.passed = passed;
        this.error = error;
    }

    public boolean isPassed() {
        return passed;
    }

    public String getError() {
        return error;
    }

    public TaskResult merge(TaskResult another) {
        final String error = this.error + "\n" + another.error;
        final boolean status = this.passed && another.passed;
        return new TaskResult(status, error);
    }

}
