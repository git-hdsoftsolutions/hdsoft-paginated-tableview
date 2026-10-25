package com.hdsoft.fxcontrols;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.DefaultProperty;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.concurrent.Task;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TableView.TableViewSelectionModel;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * A reusable, FXML / Scene Builder friendly table with a title, a search box and
 * client-side pagination. Data is pushed in from the backend with
 * {@link #loadItems(Collection)} or {@link #loadItemsAsync(Callable, Consumer)}.
 *
 * <h3>Structure (style classes for the CSS you add later)</h3>
 * <pre>
 * .paginated-table-view
 *   .ptv-header   (HBox)      .ptv-title (Label)  .ptv-search (TextField)
 *   .ptv-table    (TableView) .ptv-placeholder (Label)  rows get :drop-target / :dragging
 *   .ptv-footer   (HBox)      .ptv-page-info  .ptv-page-size  .ptv-nav-button  .ptv-page-label
 * </pre>
 * The control itself gets the pseudo-class {@code :loading} while an async load runs.
 *
 * <h3>Columns</h3>
 * Either declare them in FXML ({@code <columns>} is the default property), use the
 * Scene Builder friendly {@link #columnDefinitionsProperty() columnDefinitions} string
 * ({@code "Name=name:200; Email=email:240"}), or call {@link #addColumn}.
 *
 * @param <T> row type
 */
@DefaultProperty("columns")
public class PaginatedTableView<T> extends VBox {

    private static final PseudoClass DROP_TARGET = PseudoClass.getPseudoClass("drop-target");
    private static final PseudoClass DRAGGING = PseudoClass.getPseudoClass("dragging");
    private static final PseudoClass LOADING = PseudoClass.getPseudoClass("loading");
    private static final String AUTO_WIDTH_KEY = "ptv.autoWidth";
    private static final double FX_DEFAULT_COLUMN_WIDTH = 80.0;
    private static final Duration SEARCH_DEBOUNCE = Duration.millis(250);

    // ------------------------------------------------------------------ nodes
    private final HBox header = new HBox(8);
    private final Label titleLabel = new Label();
    private final Region headerSpacer = new Region();
    private final TextField searchField = new TextField();

    private final TableView<T> table = new TableView<>();
    private final Label placeholderLabel = new Label();

    private final HBox footer = new HBox(8);
    private final Label infoLabel = new Label();
    private final Region footerSpacer = new Region();
    private final ComboBox<Integer> pageSizeBox = new ComboBox<>();
    private final Button firstButton = new Button("\u00AB");
    private final Button prevButton = new Button("\u2039");
    private final Label pageLabel = new Label();
    private final Button nextButton = new Button("\u203A");
    private final Button lastButton = new Button("\u00BB");

    // ------------------------------------------------------------------- data
    private final ObservableList<T> master = FXCollections.observableArrayList();
    private final FilteredList<T> filtered = new FilteredList<>(master);
    private final SortedList<T> sorted = new SortedList<>(filtered);
    private final ObservableList<T> pageItems = FXCollections.observableArrayList();
    private final List<TableColumn<T, ?>> definitionColumns = new ArrayList<>();
    private final PauseTransition searchDebounce = new PauseTransition(SEARCH_DEBOUNCE);

    private BiPredicate<T, String> searchFilter;
    private Consumer<List<T>> onItemsReordered;
    private T draggedItem;
    private boolean reordering;
    private boolean syncingPageSize;
    private boolean restoringColumnOrder;
    private List<TableColumn<T, ?>> columnOrderSnapshot = new ArrayList<TableColumn<T, ?>>();

    // ------------------------------------------------- Scene Builder properties
    private final StringProperty title = new SimpleStringProperty(this, "title", "Title");
    private final StringProperty searchPromptText = new SimpleStringProperty(this, "searchPromptText", "Search...");
    private final StringProperty placeholderText = new SimpleStringProperty(this, "placeholderText", "No data available");
    private final StringProperty loadingText = new SimpleStringProperty(this, "loadingText", "Loading...");
    private final DoubleProperty searchFieldWidth = new SimpleDoubleProperty(this, "searchFieldWidth", 220);
    private final DoubleProperty tableHeight = new SimpleDoubleProperty(this, "tableHeight", 300);
    private final DoubleProperty defaultColumnWidth = new SimpleDoubleProperty(this, "defaultColumnWidth", 120);
    private final ObjectProperty<Font> titleFont = new SimpleObjectProperty<>(this, "titleFont");
    private final ObjectProperty<Font> searchFont = new SimpleObjectProperty<>(this, "searchFont");
    private final ObjectProperty<Font> tableFont = new SimpleObjectProperty<>(this, "tableFont");
    private final BooleanProperty columnsResizable = new SimpleBooleanProperty(this, "columnsResizable", true);
    private final BooleanProperty columnsReorderable = new SimpleBooleanProperty(this, "columnsReorderable", true);
    private final BooleanProperty columnsSortable = new SimpleBooleanProperty(this, "columnsSortable", true);
    private final BooleanProperty fitColumnsToWidth = new SimpleBooleanProperty(this, "fitColumnsToWidth", false);
    private final BooleanProperty dragAndDropEnabled = new SimpleBooleanProperty(this, "dragAndDropEnabled", true);
    private final BooleanProperty showTitle = new SimpleBooleanProperty(this, "showTitle", true);
    private final BooleanProperty showSearch = new SimpleBooleanProperty(this, "showSearch", true);
    private final BooleanProperty showPager = new SimpleBooleanProperty(this, "showPager", true);
    private final BooleanProperty showPageSizeSelector = new SimpleBooleanProperty(this, "showPageSizeSelector", true);
    private final IntegerProperty rowsPerPage = new SimpleIntegerProperty(this, "rowsPerPage", 10);
    private final IntegerProperty pageIndex = new SimpleIntegerProperty(this, "pageIndex", 0);
    private final StringProperty pageSizeOptions = new SimpleStringProperty(this, "pageSizeOptions", "10,25,50,100");
    private final StringProperty columnDefinitions = new SimpleStringProperty(this, "columnDefinitions", "");
    private final ReadOnlyBooleanWrapper loading = new ReadOnlyBooleanWrapper(this, "loading", false);

    // ----------------------------------------------------------- construction
    public PaginatedTableView() {
        getStyleClass().add("paginated-table-view");
        setSpacing(8);

        buildHeader();
        buildTable();
        buildFooter();
        getChildren().addAll(header, table, footer);

        wireVisualProperties();
        wireColumnProperties();
        wireDataFlow();

        rebuildPageSizeOptions();
        applyTableHeight();
        applyTableFont(null);
        refreshPage();
    }

    private void buildHeader() {
        header.getStyleClass().add("ptv-header");
        header.setAlignment(Pos.CENTER_LEFT);
        titleLabel.getStyleClass().add("ptv-title");
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        searchField.getStyleClass().add("ptv-search");
        header.getChildren().addAll(titleLabel, headerSpacer, searchField);
    }

    private void buildTable() {
        table.getStyleClass().add("ptv-table");
        table.setMaxWidth(Double.MAX_VALUE);
        table.setItems(pageItems);

        // Sorting must span ALL pages, not just the visible one: the table's own
        // in-place sort is disabled and its comparator drives the SortedList instead.
        table.setSortPolicy(t -> true);
        sorted.comparatorProperty().bind(table.comparatorProperty());

        placeholderLabel.getStyleClass().add("ptv-placeholder");
        table.setPlaceholder(placeholderLabel);
        table.setRowFactory(tv -> createRow());
    }

    private void buildFooter() {
        footer.getStyleClass().add("ptv-footer");
        footer.setAlignment(Pos.CENTER_LEFT);
        infoLabel.getStyleClass().add("ptv-page-info");
        pageSizeBox.getStyleClass().add("ptv-page-size");
        pageLabel.getStyleClass().add("ptv-page-label");
        for (Button b : Arrays.asList(firstButton, prevButton, nextButton, lastButton)) {
            b.getStyleClass().add("ptv-nav-button");
        }
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        footer.getChildren().addAll(infoLabel, footerSpacer, pageSizeBox,
                firstButton, prevButton, pageLabel, nextButton, lastButton);

        firstButton.setOnAction(e -> pageIndex.set(0));
        prevButton.setOnAction(e -> pageIndex.set(getPageIndex() - 1));
        nextButton.setOnAction(e -> pageIndex.set(getPageIndex() + 1));
        lastButton.setOnAction(e -> pageIndex.set(pageCount() - 1));
    }

    // ------------------------------------------------------------------ wiring
    private void wireVisualProperties() {
        titleLabel.textProperty().bind(title);
        searchField.promptTextProperty().bind(searchPromptText);
        placeholderLabel.textProperty().bind(
                Bindings.when(loading).then(loadingText).otherwise(placeholderText));

        bindVisible(titleLabel, showTitle);
        bindVisible(searchField, showSearch);
        bindVisible(header, showTitle.or(showSearch));
        bindVisible(footer, showPager);
        bindVisible(pageSizeBox, showPageSizeSelector);

        searchFieldWidth.addListener((o, a, w) -> applySearchWidth());
        applySearchWidth();

        tableHeight.addListener((o, a, h) -> applyTableHeight());

        // Fonts. Title/search use the Labeled/TextInput font property (CSS can still
        // override it). The table has no font property, so a font is only applied inline
        // when tableFont is set; leave it null if you want CSS to own the table font.
        titleFont.addListener((o, a, f) -> titleLabel.setFont(f != null ? f : Font.getDefault()));
        searchFont.addListener((o, a, f) -> searchField.setFont(f != null ? f : Font.getDefault()));
        tableFont.addListener((o, a, f) -> applyTableFont(f));

        loading.addListener((o, a, l) -> pseudoClassStateChanged(LOADING, l));
    }

    private void wireColumnProperties() {
        table.getColumns().addListener((ListChangeListener<TableColumn<T, ?>>) c -> {
            if (restoringColumnOrder) {
                return;
            }
            boolean reorderOnly = false;
            while (c.next()) {
                if (c.wasAdded()) {
                    c.getAddedSubList().forEach(this::configureColumn);
                }
                // JavaFX 8 has no TableColumn.reorderable; a header drag shows up as a
                // permutation, or as a replace of the same set of columns.
                if (c.wasPermutated()
                        || (c.wasRemoved() && c.wasAdded()
                            && new HashSet<Object>(c.getRemoved()).equals(new HashSet<Object>(c.getAddedSubList())))) {
                    reorderOnly = true;
                }
            }
            if (reorderOnly && !isColumnsReorderable()) {
                restoringColumnOrder = true;
                try {
                    table.getColumns().setAll(columnOrderSnapshot);
                } finally {
                    restoringColumnOrder = false;
                }
            } else {
                columnOrderSnapshot = new ArrayList<TableColumn<T, ?>>(table.getColumns());
            }
        });
        columnsResizable.addListener((o, a, b) -> applyToAllColumns());
        columnsSortable.addListener((o, a, b) -> applyToAllColumns());
        defaultColumnWidth.addListener((o, a, b) -> applyToAllColumns());

        fitColumnsToWidth.addListener((o, a, fit) -> applyResizePolicy(fit));
        applyResizePolicy(isFitColumnsToWidth());

        columnDefinitions.addListener((o, a, b) -> rebuildDefinitionColumns());
    }

    @SuppressWarnings("deprecation") // CONSTRAINED_RESIZE_POLICY exists in every JavaFX version Scene Builder bundles
    private void applyResizePolicy(boolean fit) {
        table.setColumnResizePolicy(fit
                ? TableView.CONSTRAINED_RESIZE_POLICY
                : TableView.UNCONSTRAINED_RESIZE_POLICY);
    }

    private void wireDataFlow() {
        sorted.addListener((ListChangeListener<T>) c -> {
            if (!reordering) {
                refreshPage();
            }
        });
        pageIndex.addListener((o, a, b) -> refreshPage());

        rowsPerPage.addListener((o, a, rp) -> {
            if (!Objects.equals(pageSizeBox.getValue(), rp.intValue())) {
                rebuildPageSizeOptions();
            }
            refreshPage();
        });
        pageSizeOptions.addListener((o, a, b) -> rebuildPageSizeOptions());
        pageSizeBox.valueProperty().addListener((o, a, v) -> {
            if (!syncingPageSize && v != null && v != getRowsPerPage()) {
                setRowsPerPage(v);
            }
        });

        searchDebounce.setOnFinished(e -> applyFilter());
        searchField.textProperty().addListener((o, a, b) -> searchDebounce.playFromStart());
    }

    private static void bindVisible(Node node, ObservableValue<Boolean> visible) {
        node.visibleProperty().bind(visible);
        node.managedProperty().bind(node.visibleProperty());
    }

    // ----------------------------------------------------------- apply helpers
    private void applySearchWidth() {
        double w = getSearchFieldWidth();
        searchField.setMinWidth(w);
        searchField.setPrefWidth(w);
        searchField.setMaxWidth(w);
    }

    /** tableHeight &gt; 0 gives a fixed height; 0 or negative lets the table fill the available space. */
    private void applyTableHeight() {
        double h = getTableHeight();
        if (h > 0) {
            table.setMinHeight(h);
            table.setPrefHeight(h);
            table.setMaxHeight(h);
            VBox.setVgrow(table, Priority.NEVER);
        } else {
            table.setMinHeight(Region.USE_COMPUTED_SIZE);
            table.setPrefHeight(Region.USE_COMPUTED_SIZE);
            table.setMaxHeight(Double.MAX_VALUE);
            VBox.setVgrow(table, Priority.ALWAYS);
        }
    }

    private void applyTableFont(Font f) {
        if (f == null) {
            table.setStyle(null);
            return;
        }
        String style = f.getStyle() == null ? "" : f.getStyle().toLowerCase(Locale.ROOT);
        table.setStyle(String.format(Locale.ROOT,
                "-fx-font-family: \"%s\"; -fx-font-size: %.1fpx; -fx-font-weight: %s; -fx-font-style: %s;",
                f.getFamily(), f.getSize(),
                style.contains("bold") ? "bold" : "normal",
                style.contains("italic") || style.contains("oblique") ? "italic" : "normal"));
    }

    private void applyToAllColumns() {
        table.getColumns().forEach(this::configureColumn);
    }

    private void configureColumn(TableColumn<T, ?> col) {
        col.setResizable(isColumnsResizable());
        col.setSortable(isColumnsSortable());
        // Only columns left at JavaFX's default width (or previously auto-sized) follow defaultColumnWidth,
        // so a width set explicitly on a column always wins.
        if (col.getProperties().containsKey(AUTO_WIDTH_KEY) || col.getPrefWidth() == FX_DEFAULT_COLUMN_WIDTH) {
            col.setPrefWidth(getDefaultColumnWidth());
            col.getProperties().put(AUTO_WIDTH_KEY, Boolean.TRUE);
        }
        col.getColumns().forEach(this::configureColumn);
    }

    /** Parses {@code "Header=property:width; Header2=property2"} into PropertyValueFactory columns. */
    private void rebuildDefinitionColumns() {
        table.getColumns().removeAll(definitionColumns);
        definitionColumns.clear();

        String spec = getColumnDefinitions();
        if (spec == null || spec.trim().isEmpty()) {
            return;
        }
        for (String part : spec.split(";")) {
            part = part.trim();
            if (part.isEmpty()) {
                continue;
            }
            String header = null;
            String rest = part;
            int eq = part.indexOf('=');
            if (eq >= 0) {
                header = part.substring(0, eq).trim();
                rest = part.substring(eq + 1).trim();
            }
            String property = rest;
            double width = -1;
            int colon = rest.lastIndexOf(':');
            if (colon >= 0) {
                property = rest.substring(0, colon).trim();
                try {
                    width = Double.parseDouble(rest.substring(colon + 1).trim());
                } catch (NumberFormatException ignored) {
                    // keep default width
                }
            }
            TableColumn<T, Object> col = new TableColumn<>(header == null ? property : header);
            col.setCellValueFactory(new PropertyValueFactory<>(property));
            if (width > 0) {
                col.setPrefWidth(width);
            }
            definitionColumns.add(col);
        }
        table.getColumns().addAll(definitionColumns);
    }

    // ------------------------------------------------------------- pagination
    private int pageCount() {
        int rp = Math.max(1, getRowsPerPage());
        return Math.max(1, (int) Math.ceil(sorted.size() / (double) rp));
    }

    private void refreshPage() {
        int rp = Math.max(1, getRowsPerPage());
        int pages = pageCount();
        int idx = Math.min(Math.max(0, getPageIndex()), pages - 1);
        if (idx != getPageIndex()) {
            pageIndex.set(idx); // listener re-enters refreshPage()
            return;
        }

        int total = sorted.size();
        int from = idx * rp;
        int to = Math.min(from + rp, total);

        T selected = table.getSelectionModel().getSelectedItem();
        if (from < to) {
            pageItems.setAll(new ArrayList<T>(sorted.subList(from, to)));
        } else {
            pageItems.clear();
        }
        if (selected != null && pageItems.contains(selected)) {
            table.getSelectionModel().select(selected);
        }

        firstButton.setDisable(idx == 0);
        prevButton.setDisable(idx == 0);
        nextButton.setDisable(idx >= pages - 1);
        lastButton.setDisable(idx >= pages - 1);
        pageLabel.setText("Page " + (idx + 1) + " / " + pages);

        if (total == 0) {
            infoLabel.setText(master.isEmpty() ? "0 rows" : "0 of " + master.size() + " rows");
        } else {
            String info = (from + 1) + "\u2013" + to + " of " + total;
            if (total != master.size()) {
                info += " (filtered from " + master.size() + ")";
            }
            infoLabel.setText(info);
        }
    }

    private void rebuildPageSizeOptions() {
        TreeSet<Integer> sizes = new TreeSet<>();
        String opts = getPageSizeOptions();
        if (opts != null) {
            for (String s : opts.split(",")) {
                try {
                    int v = Integer.parseInt(s.trim());
                    if (v > 0) {
                        sizes.add(v);
                    }
                } catch (NumberFormatException ignored) {
                    // skip invalid token
                }
            }
        }
        sizes.add(Math.max(1, getRowsPerPage()));
        syncingPageSize = true; // the ComboBox briefly re-emits the old value while items are replaced
        try {
            pageSizeBox.getItems().setAll(sizes);
            pageSizeBox.setValue(Math.max(1, getRowsPerPage()));
        } finally {
            syncingPageSize = false;
        }
    }

    // ------------------------------------------------------------------ search
    private void applyFilter() {
        String q = searchField.getText() == null ? "" : searchField.getText().trim();
        if (q.isEmpty()) {
            filtered.setPredicate(null);
        } else {
            filtered.setPredicate(item -> matches(item, q));
        }
        pageIndex.set(0);
    }

    private boolean matches(T item, String query) {
        if (searchFilter != null) {
            return searchFilter.test(item, query);
        }
        String needle = query.toLowerCase(Locale.ROOT);
        List<TableColumn<T, ?>> leaves = new ArrayList<>();
        collectLeaves(table.getColumns(), leaves);
        for (TableColumn<T, ?> col : leaves) {
            if (!col.isVisible()) {
                continue;
            }
            ObservableValue<?> v = col.getCellObservableValue(item);
            if (v != null && v.getValue() != null
                    && v.getValue().toString().toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private void collectLeaves(List<TableColumn<T, ?>> cols, List<TableColumn<T, ?>> out) {
        for (TableColumn<T, ?> c : cols) {
            if (c.getColumns().isEmpty()) {
                out.add(c);
            } else {
                collectLeaves(c.getColumns(), out);
            }
        }
    }

    // ------------------------------------------------------------ drag & drop
    private boolean reorderAllowed() {
        // Manual order is meaningless while a column sort is active.
        return isDragAndDropEnabled() && table.getSortOrder().isEmpty();
    }

    private boolean acceptsDrag(DragEvent e) {
        if (draggedItem == null || !reorderAllowed()) {
            return false;
        }
        Object source = e.getGestureSource();
        return source instanceof TableRow && ((TableRow<?>) source).getTableView() == table;
    }

    private TableRow<T> createRow() {
        TableRow<T> row = new TableRow<>();

        row.setOnDragDetected(e -> {
            if (!reorderAllowed() || row.isEmpty()) {
                return;
            }
            Dragboard db = row.startDragAndDrop(TransferMode.MOVE);
            db.setDragView(row.snapshot(null, null));
            ClipboardContent content = new ClipboardContent();
            content.putString("ptv-row");
            db.setContent(content);
            draggedItem = row.getItem();
            row.pseudoClassStateChanged(DRAGGING, true);
            e.consume();
        });
        row.setOnDragOver(e -> {
            if (acceptsDrag(e)) {
                e.acceptTransferModes(TransferMode.MOVE);
                e.consume();
            }
        });
        row.setOnDragEntered(e -> {
            if (acceptsDrag(e) && row.getItem() != draggedItem) {
                row.pseudoClassStateChanged(DROP_TARGET, true);
            }
        });
        row.setOnDragExited(e -> row.pseudoClassStateChanged(DROP_TARGET, false));
        row.setOnDragDropped(e -> {
            boolean ok = false;
            if (acceptsDrag(e)) {
                T target = row.isEmpty()
                        ? (pageItems.isEmpty() ? null : pageItems.get(pageItems.size() - 1))
                        : row.getItem();
                ok = moveItem(draggedItem, target);
            }
            e.setDropCompleted(ok);
            e.consume();
        });
        row.setOnDragDone(e -> {
            draggedItem = null;
            row.pseudoClassStateChanged(DRAGGING, false);
            e.consume();
        });
        return row;
    }

    /** Moves {@code dragged} to the position of {@code target} inside the full (unfiltered) data list. */
    private boolean moveItem(T dragged, T target) {
        int from = master.indexOf(dragged);
        int to = master.indexOf(target);
        if (from < 0 || to < 0 || from == to) {
            return false;
        }
        reordering = true;
        try {
            master.remove(from);
            int targetIdx = master.indexOf(target);
            master.add(from < to ? targetIdx + 1 : targetIdx, dragged); // down: after target, up: before target
        } finally {
            reordering = false;
        }
        refreshPage();
        table.getSelectionModel().select(dragged);
        if (onItemsReordered != null) {
            onItemsReordered.accept(Collections.unmodifiableList(new ArrayList<T>(master)));
        }
        return true;
    }

    // ====================================================== public backend API

    /** Replaces all data. Safe to call from any thread. */
    public void loadItems(Collection<? extends T> items) {
        final List<T> copy = new ArrayList<T>();
        if (items != null) {
            copy.addAll(items);
        }
        if (Platform.isFxApplicationThread()) {
            master.setAll(copy);
        } else {
            Platform.runLater(() -> master.setAll(copy));
        }
    }

    /**
     * Runs {@code loader} on a background thread (your REST/DB call), then fills the table on the
     * FX thread. While running, {@link #loadingProperty()} is true and the placeholder shows
     * {@link #getLoadingText()}.
     */
    public void loadItemsAsync(Callable<? extends Collection<? extends T>> loader, Consumer<Throwable> onError) {
        loading.set(true);
        final Task<List<T>> task = new Task<List<T>>() {
            @Override
            protected List<T> call() throws Exception {
                return new ArrayList<T>(loader.call());
            }
        };
        task.setOnSucceeded(e -> {
            loadItems(task.getValue());
            loading.set(false);
        });
        task.setOnFailed(e -> {
            loading.set(false);
            if (onError != null) {
                onError.accept(task.getException());
            }
        });
        Thread t = new Thread(task, "ptv-loader");
        t.setDaemon(true);
        t.start();
    }

    /** The complete (unfiltered) backing list, in manual order. Modify it and the view updates. */
    public ObservableList<T> getItems() {
        return master;
    }

    /** Columns; this is the FXML default property. */
    public ObservableList<TableColumn<T, ?>> getColumns() {
        return table.getColumns();
    }

    /** Programmatic column helper using a JavaBean/JavaFX property name. */
    public <V> TableColumn<T, V> addColumn(String header, String propertyName, double width) {
        TableColumn<T, V> col = new TableColumn<>(header);
        col.setCellValueFactory(new PropertyValueFactory<>(propertyName));
        if (width > 0) {
            col.setPrefWidth(width);
        }
        table.getColumns().add(col);
        return col;
    }

    public TableViewSelectionModel<T> getSelectionModel() {
        return table.getSelectionModel();
    }

    public T getSelectedItem() {
        return table.getSelectionModel().getSelectedItem();
    }

    public ReadOnlyObjectProperty<T> selectedItemProperty() {
        return table.getSelectionModel().selectedItemProperty();
    }

    /** Custom search predicate: (row, trimmed query) -&gt; match. Default: any visible column contains the text. */
    public void setSearchFilter(BiPredicate<T, String> filter) {
        this.searchFilter = filter;
        applyFilter();
    }

    /** Called with a copy of the full list in its new order after a drag-and-drop reorder. */
    public void setOnItemsReordered(Consumer<List<T>> handler) {
        this.onItemsReordered = handler;
    }

    public void goToPage(int zeroBasedIndex) {
        setPageIndex(zeroBasedIndex);
    }

    public void clearSearch() {
        searchField.clear();
    }

    public String getSearchText() {
        return searchField.getText();
    }

    public void setSearchText(String text) {
        searchField.setText(text);
    }

    /** Escape hatches for advanced customisation. */
    public TableView<T> getTableView() {
        return table;
    }

    public TextField getSearchField() {
        return searchField;
    }

    public Label getTitleLabel() {
        return titleLabel;
    }

    // ===================================== JavaFX properties (Scene Builder)

    public String getTitle() { return title.get(); }
    public void setTitle(String v) { title.set(v); }
    public StringProperty titleProperty() { return title; }

    public String getSearchPromptText() { return searchPromptText.get(); }
    public void setSearchPromptText(String v) { searchPromptText.set(v); }
    public StringProperty searchPromptTextProperty() { return searchPromptText; }

    public String getPlaceholderText() { return placeholderText.get(); }
    public void setPlaceholderText(String v) { placeholderText.set(v); }
    public StringProperty placeholderTextProperty() { return placeholderText; }

    public String getLoadingText() { return loadingText.get(); }
    public void setLoadingText(String v) { loadingText.set(v); }
    public StringProperty loadingTextProperty() { return loadingText; }

    public double getSearchFieldWidth() { return searchFieldWidth.get(); }
    public void setSearchFieldWidth(double v) { searchFieldWidth.set(v); }
    public DoubleProperty searchFieldWidthProperty() { return searchFieldWidth; }

    /** Fixed table height in px; use 0 or a negative value to fill the available space. */
    public double getTableHeight() { return tableHeight.get(); }
    public void setTableHeight(double v) { tableHeight.set(v); }
    public DoubleProperty tableHeightProperty() { return tableHeight; }

    /** Pref width for columns that have no explicit width. */
    public double getDefaultColumnWidth() { return defaultColumnWidth.get(); }
    public void setDefaultColumnWidth(double v) { defaultColumnWidth.set(v); }
    public DoubleProperty defaultColumnWidthProperty() { return defaultColumnWidth; }

    public Font getTitleFont() { return titleFont.get(); }
    public void setTitleFont(Font v) { titleFont.set(v); }
    public ObjectProperty<Font> titleFontProperty() { return titleFont; }

    public Font getSearchFont() { return searchFont.get(); }
    public void setSearchFont(Font v) { searchFont.set(v); }
    public ObjectProperty<Font> searchFontProperty() { return searchFont; }

    public Font getTableFont() { return tableFont.get(); }
    public void setTableFont(Font v) { tableFont.set(v); }
    public ObjectProperty<Font> tableFontProperty() { return tableFont; }

    public boolean isColumnsResizable() { return columnsResizable.get(); }
    public void setColumnsResizable(boolean v) { columnsResizable.set(v); }
    public BooleanProperty columnsResizableProperty() { return columnsResizable; }

    /**
     * false = users cannot drag column headers into a new order. (JavaFX 8 has no built-in switch for
     * this, so the original order is restored whenever a header drag changes it.)
     */
    public boolean isColumnsReorderable() { return columnsReorderable.get(); }
    public void setColumnsReorderable(boolean v) { columnsReorderable.set(v); }
    public BooleanProperty columnsReorderableProperty() { return columnsReorderable; }

    public boolean isColumnsSortable() { return columnsSortable.get(); }
    public void setColumnsSortable(boolean v) { columnsSortable.set(v); }
    public BooleanProperty columnsSortableProperty() { return columnsSortable; }

    /** true = columns stretch to fill the width; false = columns keep their pref width (horizontal scroll). */
    public boolean isFitColumnsToWidth() { return fitColumnsToWidth.get(); }
    public void setFitColumnsToWidth(boolean v) { fitColumnsToWidth.set(v); }
    public BooleanProperty fitColumnsToWidthProperty() { return fitColumnsToWidth; }

    /** Row drag-and-drop reordering (disabled automatically while a column sort is active). */
    public boolean isDragAndDropEnabled() { return dragAndDropEnabled.get(); }
    public void setDragAndDropEnabled(boolean v) { dragAndDropEnabled.set(v); }
    public BooleanProperty dragAndDropEnabledProperty() { return dragAndDropEnabled; }

    public boolean isShowTitle() { return showTitle.get(); }
    public void setShowTitle(boolean v) { showTitle.set(v); }
    public BooleanProperty showTitleProperty() { return showTitle; }

    public boolean isShowSearch() { return showSearch.get(); }
    public void setShowSearch(boolean v) { showSearch.set(v); }
    public BooleanProperty showSearchProperty() { return showSearch; }

    public boolean isShowPager() { return showPager.get(); }
    public void setShowPager(boolean v) { showPager.set(v); }
    public BooleanProperty showPagerProperty() { return showPager; }

    public boolean isShowPageSizeSelector() { return showPageSizeSelector.get(); }
    public void setShowPageSizeSelector(boolean v) { showPageSizeSelector.set(v); }
    public BooleanProperty showPageSizeSelectorProperty() { return showPageSizeSelector; }

    public int getRowsPerPage() { return rowsPerPage.get(); }
    public void setRowsPerPage(int v) { rowsPerPage.set(Math.max(1, v)); }
    public IntegerProperty rowsPerPageProperty() { return rowsPerPage; }

    /** Zero-based current page. */
    public int getPageIndex() { return pageIndex.get(); }
    public void setPageIndex(int v) { pageIndex.set(Math.max(0, v)); }
    public IntegerProperty pageIndexProperty() { return pageIndex; }

    /** Comma separated choices for the rows-per-page selector, e.g. {@code 10,25,50,100}. */
    public String getPageSizeOptions() { return pageSizeOptions.get(); }
    public void setPageSizeOptions(String v) { pageSizeOptions.set(v); }
    public StringProperty pageSizeOptionsProperty() { return pageSizeOptions; }

    /** Quick column setup usable from Scene Builder: {@code Name=name:200; Email=email:240; Age=age}. */
    public String getColumnDefinitions() { return columnDefinitions.get(); }
    public void setColumnDefinitions(String v) { columnDefinitions.set(v); }
    public StringProperty columnDefinitionsProperty() { return columnDefinitions; }

    public boolean isLoading() { return loading.get(); }
    public ReadOnlyBooleanProperty loadingProperty() { return loading.getReadOnlyProperty(); }
}
