// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.data.osm.event;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import javax.swing.SwingUtilities;

import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.data.SelectionChangedListener;
import org.openstreetmap.josm.data.osm.DataSelectionListener;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.event.DatasetEventManager.FireMode;
import org.openstreetmap.josm.gui.layer.MainLayerManager;
import org.openstreetmap.josm.gui.layer.MainLayerManager.ActiveLayerChangeEvent;
import org.openstreetmap.josm.gui.layer.MainLayerManager.ActiveLayerChangeListener;

/**
 * Similar like {@link DatasetEventManager}, just for selection events.
 *
 * It allows to register listeners to global selection events for the selection in the current edit layer.
 *
 * If you want to listen to selections to a specific data layer,
 * you can register a listener to that layer by using {@link DataSet#addSelectionListener(DataSelectionListener)}
 *
 * @since 2912
 */
public class SelectionEventManager implements DataSelectionListener, ActiveLayerChangeListener {

    private static final SelectionEventManager instance = new SelectionEventManager();

    /**
     * Returns the unique instance.
     * @return the unique instance
     */
    public static SelectionEventManager getInstance() {
        return instance;
    }

    private abstract static class AbstractListenerInfo {
        abstract void fire(SelectionChangeEvent event);
    }

    private static class ListenerInfo extends AbstractListenerInfo {
        private final SelectionChangedListener listener;

        ListenerInfo(SelectionChangedListener listener) {
            this.listener = listener;
        }

        @Override
        void fire(SelectionChangeEvent event) {
            listener.selectionChanged(event.getSelection());
        }

        @Override
        public int hashCode() {
            return Objects.hash(listener);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ListenerInfo that = (ListenerInfo) o;
            return Objects.equals(listener, that.listener);
        }
    }

    private static class DataListenerInfo extends AbstractListenerInfo {
        private final DataSelectionListener listener;

        DataListenerInfo(DataSelectionListener listener) {
            this.listener = listener;
        }

        @Override
        void fire(SelectionChangeEvent event) {
            listener.selectionChanged(event);
        }

        @Override
        public int hashCode() {
            return Objects.hash(listener);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            DataListenerInfo that = (DataListenerInfo) o;
            return Objects.equals(listener, that.listener);
        }
    }

    private final CopyOnWriteArrayList<AbstractListenerInfo> inEDTListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<AbstractListenerInfo> immedatelyListeners = new CopyOnWriteArrayList<>();

    /**
     * Constructs a new {@code SelectionEventManager}.
     */
    protected SelectionEventManager() {
        MainLayerManager layerManager = Main.getLayerManager();
        // We do not allow for destructing this object.
        // Currently, this is a singleton class, so this is not required.
        layerManager.addAndFireActiveLayerChangeListener(this);
    }

    /**
     * Registers a new {@code SelectionChangedListener}.
     *
     * It is preferred to add a DataSelectionListener - that listener will receive more information about the event.
     * @param listener listener to add
     * @param fireMode Set this to IN_EDT_CONSOLIDATED if you want the event to be fired in the EDT thread.
     *                 Set it to IMMEDIATELY if you want the event to fire in the thread that caused the selection update.
     */
    public void addSelectionListener(SelectionChangedListener listener, FireMode fireMode) {
        if (fireMode == FireMode.IN_EDT) {
            throw new UnsupportedOperationException("IN_EDT mode not supported, you probably want to use IN_EDT_CONSOLIDATED.");
        } else if (fireMode == FireMode.IN_EDT_CONSOLIDATED) {
            inEDTListeners.addIfAbsent(new ListenerInfo(listener));
        } else {
            immedatelyListeners.addIfAbsent(new ListenerInfo(listener));
        }
    }

    /**
     * Adds a selection listener that gets notified for selections immediately.
     * @param listener The listener to add.
     * @since 12098
     */
    public void addSelectionListener(DataSelectionListener listener) {
        immedatelyListeners.addIfAbsent(new DataListenerInfo(listener));
    }

    /**
     * Adds a selection listener that gets notified for selections later in the EDT thread.
     * Events are sent in the right order but may be delayed.
     * @param listener The listener to add.
     * @since 12098
     */
    public void addSelectionListenerForEdt(DataSelectionListener listener) {
        inEDTListeners.addIfAbsent(new DataListenerInfo(listener));
    }

    /**
     * Unregisters a {@code SelectionChangedListener}.
     * @param listener listener to remove
     */
    public void removeSelectionListener(SelectionChangedListener listener) {
        remove(new ListenerInfo(listener));
    }

    /**
     * Unregisters a {@code DataSelectionListener}.
     * @param listener listener to remove
     * @since 12098
     */
    public void removeSelectionListener(DataSelectionListener listener) {
        remove(new DataListenerInfo(listener));
    }

    private void remove(AbstractListenerInfo searchListener) {
        inEDTListeners.remove(searchListener);
        immedatelyListeners.remove(searchListener);
    }

    @Override
    public void activeOrEditLayerChanged(ActiveLayerChangeEvent e) {
        DataSet oldDataSet = e.getPreviousEditDataSet();
        if (oldDataSet != null) {
            // Fake a selection removal
            // Relying on this allows components to not have to monitor layer changes.
            // If we would not do this, e.g. the move command would have a hard time tracking which layer
            // the last moved selection was in.
            SelectionReplaceEvent event = new SelectionReplaceEvent(oldDataSet,
                    new HashSet<>(oldDataSet.getAllSelected()), Stream.empty());
            selectionChanged(event);
            oldDataSet.removeSelectionListener(this);
        }
        DataSet newDataSet = e.getSource().getEditDataSet();
        if (newDataSet != null) {
            newDataSet.addSelectionListener(this);
            // Fake a selection add
            SelectionReplaceEvent event = new SelectionReplaceEvent(newDataSet,
                    Collections.emptySet(), newDataSet.getAllSelected().stream());
            selectionChanged(event);
        }
    }

    @Override
    public void selectionChanged(SelectionChangeEvent event) {
        fireEvent(immedatelyListeners, event);
        SwingUtilities.invokeLater(() -> fireEvent(inEDTListeners, event));
    }

    private static void fireEvent(List<AbstractListenerInfo> listeners, SelectionChangeEvent event) {
        for (AbstractListenerInfo listener: listeners) {
            listener.fire(event);
        }
    }

    /**
     * Only to be used during unit tests, to reset the state. Do not use it in plugins/other code.
     * Called after the layer manager was reset by the test framework.
     */
    public void resetState() {
        inEDTListeners.clear();
        immedatelyListeners.clear();
        Main.getLayerManager().addAndFireActiveLayerChangeListener(this);
    }
}
