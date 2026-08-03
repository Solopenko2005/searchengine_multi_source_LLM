package searchengine.config;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static searchengine.services.LemmaService.logger;

/**
 * Единое состояние индексации для ВСЕХ источников информации сразу
 * (обход сайтов + индексация загруженных документов DOCX/PDF).
 * <p>
 * Благодаря общему состоянию:
 * <ul>
 *     <li>кнопка «Начать / Остановить» управляет всеми источниками одновременно;</li>
 *     <li>остановка ({@link #requestStop()}) прерывает и обход сайтов, и пакетную загрузку документов;</li>
 *     <li>флаг «идёт индексация» ({@link #isIndexingInProgress()}) корректно виден в статистике
 *         и автоматически снимается, когда завершилась последняя единица работы.</li>
 * </ul>
 * Единицей работы считается либо обход одного сайта, либо один пакет загруженных документов.
 * Каждая единица регистрируется через {@link #taskStarted()} и снимается через {@link #taskFinished()}.
 */
@Component
public class IndexingState {

    private final AtomicBoolean indexingInProgress = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final AtomicInteger activeTasks = new AtomicInteger(0);
    private final AtomicInteger totalItems = new AtomicInteger(0);
    private final AtomicInteger completedItems = new AtomicInteger(0);
    private final AtomicInteger failedItems = new AtomicInteger(0);
    private final AtomicReference<String> currentOperation = new AtomicReference<>("");

    public boolean isIndexingInProgress() {
        return indexingInProgress.get();
    }

    public boolean isStopRequested() {
        return stopRequested.get();
    }

    /** Количество активных единиц работы (сайтов + пакетов документов), выполняющихся прямо сейчас. */
    public int getActiveTasks() {
        return activeTasks.get();
    }

    public int getTotalItems() { return totalItems.get(); }
    public int getCompletedItems() { return completedItems.get(); }
    public int getFailedItems() { return failedItems.get(); }
    public String getCurrentOperation() { return currentOperation.get(); }

    public void beginOperation(String operation, int total) {
        currentOperation.set(operation == null ? "" : operation);
        totalItems.set(Math.max(0, total));
        completedItems.set(0);
        failedItems.set(0);
    }

    public void itemCompleted() { completedItems.incrementAndGet(); }
    public void itemFailed() { failedItems.incrementAndGet(); }

    /**
     * Регистрирует начало новой единицы работы. При этом индексация помечается как выполняющаяся.
     */
    public synchronized void taskStarted() {
        activeTasks.incrementAndGet();
        indexingInProgress.set(true);
    }

    /**
     * Регистрирует завершение единицы работы. Когда активных задач не осталось,
     * индексация считается полностью завершённой: снимается флаг выполнения и
     * сбрасывается запрос остановки (чтобы поиск и повторный запуск снова работали).
     */
    public synchronized void taskFinished() {
        if (activeTasks.decrementAndGet() <= 0) {
            activeTasks.set(0);
            indexingInProgress.set(false);
            stopRequested.set(false);
            currentOperation.set("");
            logger.info("Индексация завершена: активных задач не осталось");
        }
    }

    /** Запрашивает остановку индексации всех источников. */
    public void requestStop() {
        stopRequested.set(true);
        logger.info("Запрошена остановка индексации всех источников");
    }

    /** Сбрасывает запрос остановки (вызывается перед запуском новой индексации). */
    public void clearStop() {
        stopRequested.set(false);
        currentOperation.set("");
        totalItems.set(0);
        completedItems.set(0);
        failedItems.set(0);
    }

    /** Полный сброс состояния. */
    public synchronized void reset() {
        activeTasks.set(0);
        indexingInProgress.set(false);
        stopRequested.set(false);
    }

    // --- Методы для обратной совместимости с существующим кодом ---

    public synchronized boolean setIndexingIfAvailable() {
        if (indexingInProgress.compareAndSet(false, true)) {
            stopRequested.set(false);
            return true;
        }
        return false;
    }

    public void setIndexingInProgress(boolean value) {
        indexingInProgress.set(value);
    }
}
