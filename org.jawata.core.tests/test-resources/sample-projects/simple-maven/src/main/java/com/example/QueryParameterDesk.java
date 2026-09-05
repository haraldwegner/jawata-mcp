package com.example;

/**
 * A caller of {@link QueryParameterTargets} in ANOTHER FILE — the half of row 55 that a
 * single-file fixture cannot show. The engine must rewrite this call to evaluate the query
 * itself and pass the answer, and nothing here is pointed at by the test.
 */
public class QueryParameterDesk {

    private final QueryParameterTargets targets = new QueryParameterTargets();

    public int today() {
        return targets.heatingPlan(7);
    }
}
