package de.beosign.snakeyamlanno.property;

import java.util.List;

import de.beosign.snakeyamlanno.type.WorkingPerson;

public class Company {
    private List<WorkingPerson> workingPersons;

    public List<WorkingPerson> getWorkingPersons() {
        return workingPersons;
    }

    public void setWorkingPersons(List<WorkingPerson> workingPersons) {
        this.workingPersons = workingPersons;
    }

    @Override
    public String toString() {
        return "Company [workingPersons=" + workingPersons + "]";
    }

}