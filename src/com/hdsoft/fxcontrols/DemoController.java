package com.hdsoft.fxcontrols;

import javafx.fxml.FXML;

public class DemoController {

    @FXML
    private PaginatedTableView<Person> peopleTable;

    @FXML
    private void initialize() {
        // Backend call runs off the FX thread; the table fills itself when it returns.
        peopleTable.loadItemsAsync(BackendService::fetchEmployees, Throwable::printStackTrace);

        peopleTable.setOnItemsReordered(list -> System.out.println("New order: first = " + list.get(0) + ", total = " + list.size()));

        peopleTable.selectedItemProperty().addListener((o, a, p) -> System.out.println("Selected: " + p));
    }
}
