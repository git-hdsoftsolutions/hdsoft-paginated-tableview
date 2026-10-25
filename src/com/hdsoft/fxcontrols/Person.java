package com.hdsoft.fxcontrols;

/** Plain model. PropertyValueFactory finds getters (or xxxProperty() methods) on public classes. */
public class Person {
    private final String name;
    private final String email;
    private final String department;
    private final int age;

    public Person(String name, String email, String department, int age) {
        this.name = name;
        this.email = email;
        this.department = department;
        this.age = age;
    }

    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getDepartment() { return department; }
    public int getAge() { return age; }

    @Override
    public String toString() { return name; }
}
