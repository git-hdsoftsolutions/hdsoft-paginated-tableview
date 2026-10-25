package com.hdsoft.fxcontrols;

import java.util.ArrayList;
import java.util.List;

/** Stand-in for your REST client / DAO. */
public final class BackendService {

    private BackendService() {}

    public static List<Person> fetchEmployees() throws InterruptedException {
        Thread.sleep(800); // simulate network latency
        String[] first = {"Asha", "Ravi", "Meera", "Kabir", "Ishita", "Arjun", "Nila", "Dev", "Priya", "Rohan"};
        String[] last = {"Sharma", "Das", "Borah", "Gogoi", "Nair", "Iyer", "Singh", "Khan", "Patel", "Bora"};
        String[] dept = {"Engineering", "Finance", "HR", "Sales", "Support"};
        List<Person> out = new ArrayList<>();
        for (int i = 0; i < 657; i++) {
            String n = first[i % first.length] + " " + last[(i * 3) % last.length];
            out.add(new Person(n, n.toLowerCase().replace(' ', '.') + "@example.com",
                    dept[i % dept.length], 22 + (i * 7) % 35));
        }
        return out;
    }
}
