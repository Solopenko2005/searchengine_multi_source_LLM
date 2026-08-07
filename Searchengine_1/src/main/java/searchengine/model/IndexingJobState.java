package searchengine.model;

public enum IndexingJobState {
    QUEUED,
    RUNNING,
    STOPPING,
    STOPPED,
    COMPLETED,
    FAILED
}
