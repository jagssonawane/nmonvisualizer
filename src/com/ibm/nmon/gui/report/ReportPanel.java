package com.ibm.nmon.gui.report;

import org.slf4j.Logger;

import java.util.BitSet;
import java.util.List;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeEvent;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;

import javax.swing.event.ChangeListener;
import javax.swing.event.ChangeEvent;

import org.jfree.chart.JFreeChart;

import com.ibm.nmon.analysis.Statistic;

import com.ibm.nmon.chart.definition.*;

import com.ibm.nmon.data.DataSet;
import com.ibm.nmon.data.definition.DataDefinition;

import com.ibm.nmon.gui.Styles;

import com.ibm.nmon.gui.chart.*;

import com.ibm.nmon.gui.chart.builder.ChartBuilderPlugin;
import com.ibm.nmon.gui.chart.builder.ChartFormatter;
import com.ibm.nmon.gui.main.NMONVisualizerGui;
import com.ibm.nmon.gui.util.ItemProgressDialog;

import com.ibm.nmon.interval.IntervalListener;
import com.ibm.nmon.interval.Interval;

/**
 * <p>
 * JTabbed pane for displaying a set of related charts. Charts are defined using {@link BaseChartDefinition}. Each chart
 * will appear on a tab named by {@link BaseChartDefinition#getShortName() getShortName()}.
 * </p>
 * 
 * <p>
 * This class listens for {@link IntervalListener interval} events as well as time zone and granularity changes. New
 * {@link DataSet data} can be added to or removed from reports at run time (if the given chart definitions should
 * display values from more than one data set).
 * </p>
 * 
 * <p>
 * This class also ensures that tabs will only be created for charts that are supported by the given data sets. This
 * allows multiple chart definitions with the same short name to be passed in if they match different data sets (i.e.
 * different hostnames or operating systems).
 * </p>
 */
public final class ReportPanel extends JTabbedPane implements PropertyChangeListener, IntervalListener {
    private static final long serialVersionUID = 6377401207979477789L;

    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ReportPanel.class);

    public static enum MultiplexMode {
        NONE, BY_TYPE, BY_FIELD
    };

    private final NMONVisualizerGui gui;
    private final JFrame parent;

    private final List<DataSet> dataSets;

    private final String reportCacheKey;
    private MultiplexMode multiplexMode;
    private List<BaseChartDefinition> chartsInUse;
    private BitSet chartNeedsUpdate;

    private final ChartFactory chartFactory;

    // ignore chart updates when tabs are being built
    private boolean buildingTabs;

    private int previousTab = -1;

    public ReportPanel(NMONVisualizerGui gui, String reportCacheKey, DataSet data) {
        this(gui, gui.getMainFrame(), reportCacheKey, java.util.Collections.singletonList(data), MultiplexMode.NONE);
    }

    public ReportPanel(NMONVisualizerGui gui, String reportCacheKey) {
        this(gui, gui.getMainFrame(), reportCacheKey, new java.util.ArrayList<DataSet>(), MultiplexMode.NONE);
    }

    public ReportPanel(NMONVisualizerGui gui, JFrame parent, String reportCacheKey, List<DataSet> dataSets,
            MultiplexMode multiplexMode) {
        super();

        setTabLayoutPolicy(SCROLL_TAB_LAYOUT);
        this.chartFactory = new ChartFactory(gui);
        this.chartFactory.setGranularity(gui.getGranularity());
        this.chartFactory.setFormatter(gui.getChartFormatter());

        this.gui = gui;
        this.parent = parent;
        this.dataSets = dataSets;
        this.reportCacheKey = reportCacheKey;
        this.multiplexMode = multiplexMode;
        this.chartsInUse = java.util.Collections.emptyList();

        buildTabs(gui);

        addChangeListener(new ChangeListener() {
            @Override
            public void stateChanged(ChangeEvent e) {
                // no need to update the chart if the tabs are still being built
                if (!buildingTabs) {
                    int idx = getSelectedIndex();

                    if (idx != -1) {
                        if (!updateChart()) {
                            // still need to notify listeners that the chart is now showing
                            firePropertyChange("chart", null, getChartPanel(idx));
                        }

                        if ((previousTab != -1) && (previousTab < getTabCount())) {
                            getChartPanel(previousTab).setEnabled(false);
                        }

                        getChartPanel(idx).setEnabled(true);
                        previousTab = idx;
                    }
                }
            }
        });

        setEnabled(false);

        gui.getIntervalManager().addListener(this);
        gui.addPropertyChangeListener("granularity", this);
        gui.addPropertyChangeListener("timeZone", this);
        gui.addPropertyChangeListener("lineChartLegend", this);
        gui.addPropertyChangeListener("chartFormatter", this);
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (enabled != isEnabled()) {
            super.setEnabled(enabled);

            chartFactory.setGranularity(gui.getGranularity());
            chartFactory.setFormatter(gui.getChartFormatter());
            chartFactory.showLegends(gui.getBooleanProperty("lineChartLegend"));

            if ((chartsInUse != null) && !chartsInUse.isEmpty()) {
                int idx = getSelectedIndex();

                if (enabled) {
                    if (idx != -1) {
                        if (!updateChart()) {
                            // still need to notify listeners that the chart is now showing
                            firePropertyChange("chart", null, getChartPanel(idx));
                        }
                    }
                }
                else {
                    if (idx != -1) {
                        // notify listeners that the chart is not showing
                        getChartPanel().clearChart();
                        // ensure chart is recreated when re-enabled
                        chartNeedsUpdate.set(idx);
                        // chart panel will already have fired a PropertyChange event
                    }
                }

                if (idx != -1) {
                    getChartPanel(idx).setEnabled(enabled);
                    previousTab = idx;
                }

                // leave the listeners enabled
                // the heavy work is in updateChart() which checks for enabled too
            }
        }
    }

    public MultiplexMode getMultiplexMode() {
        return multiplexMode;
    }

    public void setMultiplexMode(MultiplexMode multiplexMode) {
        if (multiplexMode == null) {
            multiplexMode = MultiplexMode.NONE;
        }

        if (this.multiplexMode != multiplexMode) {
            this.multiplexMode = multiplexMode;

            buildTabs(gui);
            resetReport();
        }
    }

    public void setData(Iterable<? extends DataSet> dataSets) {
        this.dataSets.clear();

        for (DataSet data : dataSets) {
            this.dataSets.add(data);
        }

        java.util.Collections.sort(this.dataSets);
        buildTabs(gui);
        resetReport();
    }

    public void addData(DataSet data) {
        if (!dataSets.contains(data)) {
            dataSets.add(data);
            java.util.Collections.sort(dataSets);
            buildTabs(gui);
            resetReport();
        }
    }

    public void removeData(DataSet data) {
        if (dataSets.remove(data)) {
            java.util.Collections.sort(dataSets);
            buildTabs(gui);
            resetReport();
        }
    }

    public void clearData() {
        dataSets.clear();
        buildTabs(gui);
        resetReport();
    }

    // mark all charts as invalid; update the current one
    public void resetReport() {
        if (chartNeedsUpdate != null) {
            chartNeedsUpdate.set(0, chartNeedsUpdate.size(), true);
        }

        updateChart();
    }

    // update the current chart if enabled
    // note that clearing / setting the chart fires a property change event
    // return false if this did not happen so callers can fire the event regardless
    private boolean updateChart() {
        if (isEnabled() && (getTabCount() != 0)) {
            int index = getSelectedIndex();

            if ((index >= 0) && !chartsInUse.isEmpty()) {
                BaseChartPanel chartPanel = getChartPanel(index);

                if (chartNeedsUpdate.get(index)) {
                    if (dataSets.isEmpty()) {
                        chartPanel.clearChart();
                    }
                    else {
                        createChart(index);
                    }

                    chartNeedsUpdate.clear(index);
                    return true;
                }
            }
        }

        return false;
    }

    public BaseChartPanel getChartPanel() {
        int index = getSelectedIndex();

        if (index == -1) {
            return null;
        }
        else {
            return getChartPanel(index);
        }
    }

    private BaseChartPanel getChartPanel(int index) {
        return (BaseChartPanel) getComponentAt(index);
    }

    public int getPreviousTab() {
        return previousTab;
    }

    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        if ("chart".equals(evt.getPropertyName())) {
            // called by chart panels when the chart changes
            // propagate chart events to listeners
            firePropertyChange(evt.getPropertyName(), evt.getOldValue(), evt.getNewValue());
        }
        else if ("granularity".equals(evt.getPropertyName())) {
            int newGranularity = (Integer) evt.getNewValue();

            chartFactory.setGranularity(newGranularity);

            // always update line charts on granularity changes
            // bar charts and interval line charts do not need to be updated unless the stat is
            // granularity max
            for (int i = 0; i < chartsInUse.size(); i++) {
                BaseChartDefinition chartDefinition = chartsInUse.get(i);

                if (chartDefinition.getClass().equals(IntervalChartDefinition.class)
                        || chartDefinition.getClass().equals(BarChartDefinition.class)) {
                    for (DataDefinition definition : chartDefinition.getData()) {
                        if (definition.getStatistic() == Statistic.GRANULARITY_MAXIMUM) {
                            chartNeedsUpdate.set(i);
                            break;
                        }
                    }
                }
                else {
                    chartNeedsUpdate.set(i);
                }
            }

            updateChart();
        }
        else if ("annotation".equals(evt.getPropertyName())) {
            // called by chart panels when annotations / markers are added
            // all charts except the current need to be updated to show the new annotation
            if (chartNeedsUpdate != null) {
                chartNeedsUpdate.set(0, chartNeedsUpdate.size(), true);
                chartNeedsUpdate.flip(getSelectedIndex());
            }

            firePropertyChange(evt.getPropertyName(), evt.getOldValue(), evt.getNewValue());
        }
        else if (evt.getPropertyName().startsWith("highlighted")) {
            // called by chart panels when an element is highlighted
            // propagate chart events to listeners
            firePropertyChange(evt.getPropertyName(), evt.getOldValue(), evt.getNewValue());
        }
        else if ("timeZone".equals(evt.getPropertyName())) {
            // assume bar charts do not need to be updated and linecharts handle this internally
            // interval charts may have unnamed intervals that need to be re-displayed
            // just recreate the chart
            updateIntervalCharts();
        }
        else if ("chartFormatter".equals(evt.getPropertyName())) {
            chartFactory.setFormatter((ChartFormatter) evt.getNewValue());
            resetReport();
        }
        else if ("lineChartLegend".equals(evt.getPropertyName())) {
            chartFactory.showLegends((Boolean) evt.getNewValue());

            for (int i = 0; i < chartsInUse.size(); i++) {
                BaseChartDefinition chartDefinition = chartsInUse.get(i);

                if (chartDefinition.getClass().equals(LineChartDefinition.class)) {
                    chartNeedsUpdate.set(i);
                }
            }

            updateChart();
        }
    }

    @Override
    public void intervalAdded(Interval interval) {
        updateIntervalCharts();
    }

    @Override
    public void intervalRemoved(Interval interval) {
        updateIntervalCharts();
    }

    @Override
    public void intervalsCleared() {
        updateIntervalCharts();
    }

    @Override
    public void currentIntervalChanged(Interval interval) {
        chartFactory.setInterval(interval);

        // update non-interval charts
        for (int i = 0; i < chartsInUse.size(); i++) {
            if (!chartsInUse.get(i).getClass().equals(IntervalChartDefinition.class)) {
                chartNeedsUpdate.set(i);
            }
        }

        updateChart();
    }

    @Override
    public void intervalRenamed(Interval interval) {
        updateIntervalCharts();
    }

    @Override
    public void removeAll() {
        if (!chartsInUse.isEmpty()) {
            for (int i = 0; i < getTabCount(); i++) {
                // buildTabs() adds each chart as a listener, so remove it when the tabs change
                BaseChartPanel chartPanel = getChartPanel(i);

                chartPanel.setEnabled(false);
                chartPanel.clearChart();
                chartPanel.removePropertyChangeListener(this);
            }
        }

        super.removeAll();

        chartsInUse = java.util.Collections.emptyList();
    }

    public void dispose() {
        gui.getIntervalManager().removeListener(this);
        gui.removePropertyChangeListener("granularity", this);
        gui.removePropertyChangeListener("timeZone", this);
        gui.removePropertyChangeListener("lineChartLegend", this);

        // clean up references to charts
        removeAll();
    }

    private void updateIntervalCharts() {
        // interval charts need to be updated
        for (int i = 0; i < chartsInUse.size(); i++) {
            if (chartsInUse.get(i).getClass().equals(IntervalChartDefinition.class)) {
                chartNeedsUpdate.set(i);
            }
        }

        updateChart();
    }

    // no need to recalculate granularity on data changes because NMONVisualizerApp recalculates min
    // and max system times on data changes, which also changes the interval if it is the default

    private void buildTabs(NMONVisualizerGui gui) {
        buildingTabs = true;

        // note remove all needs to know if charts existed previously, order matters here
        removeAll();

        if (gui.getReportCache().getReport(reportCacheKey).isEmpty()) {
            addTab("No Charts", createNoReportsLabel("No Charts Defined!"));
        }
        else {
            if (dataSets.isEmpty()) {
                addTab("No Charts", createNoReportsLabel("No Parsed Data!"));
                buildingTabs = false;

                return;
            }

            if (multiplexMode == MultiplexMode.NONE) {
                chartsInUse = gui.getReportCache().getReport(reportCacheKey, dataSets);
            }
            else {
                if (dataSets.size() > 1) {
                    LOGGER.warn("not multiplexing charts when there is more than one dataset");
                    chartsInUse = gui.getReportCache().getReport(reportCacheKey, dataSets);
                }
                else if (multiplexMode == MultiplexMode.BY_TYPE) {
                    chartsInUse = gui.getReportCache().multiplexChartsAcrossTypes(reportCacheKey, dataSets.get(0),
                            true);
                }
                else if (multiplexMode == MultiplexMode.BY_FIELD) {
                    chartsInUse = gui.getReportCache().multiplexChartsAcrossFields(reportCacheKey, dataSets.get(0),
                            true);
                }
            }

            if (chartsInUse.isEmpty()) {
                addTab("No Charts", createNoReportsLabel("No Charts for Currently Parsed Data!"));
                chartsInUse = java.util.Collections.emptyList();
                chartNeedsUpdate = null;
            }
            else {
                chartNeedsUpdate = new BitSet(chartsInUse.size());
                chartNeedsUpdate.set(0, chartNeedsUpdate.size(), true);

                for (BaseChartDefinition report : chartsInUse) {
                    BaseChartPanel chartPanel = null;

                    if (report.getClass() == LineChartDefinition.class) {
                        // handle special case when not scaling CPUs
                        // need to allow the axis to scale past 100, up to maximum CPU value
                        if (report.getTitle().equals("CPU Utilization by Process")
                                && !gui.getBooleanProperty("scaleProcessesByCPUs")) {
                            ((LineChartDefinition) report).setUsePercentYAxis(false);
                        }

                        chartPanel = new LineChartPanel(gui, parent);
                    }
                    else if (report.getClass() == IntervalChartDefinition.class) {
                        chartPanel = new IntervalChartPanel(gui, parent);
                    }
                    else if (report.getClass() == BarChartDefinition.class) {
                        chartPanel = new BarChartPanel(gui, parent);
                    }
                    else if (report.getClass() == HistogramChartDefinition.class) {
                        chartPanel = new LineChartPanel(gui, parent);
                    }
                    else {
                        LOGGER.error("cannot create chart panel for {} ({})", report.getShortName(),
                                report.getClass().getSimpleName());
                    }

                    // this class will receive each chart's change events and forward them rather
                    // than expose each chart as a separate listener
                    chartPanel.addPropertyChangeListener(this);
                    addTab(report.getShortName(), chartPanel);
                }
            }
        }

        if (previousTab > getTabCount()) {
            previousTab = -1;
        }

        buildingTabs = false;
    }

    private void createChart(int index) {
        BaseChartDefinition definition = chartsInUse.get(index);

        JFreeChart chart = chartFactory.createChart(definition, dataSets);

        // setChart will fire the event that updates the data table
        BaseChartPanel chartPanel = getChartPanel(index);

        chartPanel.setSaveSize(definition.getWidth(), definition.getHeight());
        chartPanel.setChart(chart);
    }

    public void saveAllCharts(final String directory) {
        final ItemProgressDialog progress = new ItemProgressDialog(parent, "Saving Charts...", getTabCount());

        if (getTabCount() == 1) {
            if (getComponentAt(0) instanceof JLabel) {
                return;
            }
        }

        // This code is a mess of things running in and out of the Swing Event Thread mostly to
        // allow the progress dialog to be modal. If it was not modal, other issues would arise if
        // users could continue to click on the UI while this code is trying to change tabs, etc.
        // The current implementation seems to work correctly WRT updating the progress bar and not
        // causing any exceptions due to events firing when tabs / charts are changed. Why this is,
        // however, is somewhat of a multi-threaded mystery.
        Thread saver = new Thread(new Runnable() {
            @Override
            public void run() {
                final int originalTab = getSelectedIndex();

                // start modal dialog must not happen directly in the Swing thread or no other code
                // will run until it is closed
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        progress.setVisible(true);
                    }
                });

                for (int i = 0; i < getTabCount(); i++) {
                    final String finalName = chartsInUse.get(i).getShortName();

                    // invokeLater() ensures the name is set before the progress is updated
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            progress.setCurrentItem(finalName);
                        }
                    });

                    final int n = i;

                    // wait here to ensure the chart actually exists before trying to save it
                    // save is in the event thread too since the chart object is manipulated when
                    // saved
                    // blocking the event thread is OK since there is a modal dialog anyway
                    try {
                        SwingUtilities.invokeAndWait(new Runnable() {
                            @Override
                            public void run() {
                                setSelectedIndex(n);
                                getChartPanel(n).saveChart(directory, finalName);
                            }
                        });
                    }
                    catch (Exception e) {
                        LOGGER.warn("error saving chart " + finalName, e);
                        continue;
                    }

                    // updating the progress does not work correctly when put in the previous
                    // invokeLater() call
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            progress.updateProgress();
                        }
                    });
                } // end for

                // wait here so that the progress dialog finishes updating before disappearing
                try {
                    SwingUtilities.invokeAndWait(new Runnable() {
                        @Override
                        public void run() {
                            setSelectedIndex(originalTab);
                            progress.dispose();
                        }
                    });
                }
                catch (Exception e) {
                    LOGGER.warn("error closing progress dialog", e);
                }
            }
        });

        saver.start();
    }

    private JLabel createNoReportsLabel(String toDisplay) {
        JLabel label = new JLabel(toDisplay);
        label.setFont(Styles.LABEL_ERROR.deriveFont(Styles.LABEL_ERROR.getSize() * 1.5f));
        label.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        label.setBackground(java.awt.Color.WHITE);
        label.setForeground(Styles.ERROR_COLOR);
        label.setOpaque(true);

        return label;
    }
}
