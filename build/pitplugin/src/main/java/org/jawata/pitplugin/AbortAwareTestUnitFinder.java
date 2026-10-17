package org.jawata.pitplugin;

import java.util.List;
import java.util.stream.Collectors;

import org.pitest.testapi.TestUnit;
import org.pitest.testapi.TestUnitExecutionListener;
import org.pitest.testapi.TestUnitFinder;

/** Finds tests with PIT's own finder and hands each one back as a unit that runs abort-aware. */
final class AbortAwareTestUnitFinder implements TestUnitFinder {

    private final TestUnitFinder stock;

    AbortAwareTestUnitFinder(TestUnitFinder stock) {
        this.stock = stock;
    }

    @Override
    public List<TestUnit> findTestUnits(Class<?> testClass, TestUnitExecutionListener listener) {
        return stock.findTestUnits(testClass, listener).stream()
            .map(found -> (TestUnit) new AbortAwareTestUnit(found.getDescription(), testClass))
            .collect(Collectors.toList());
    }
}
