package io.github.bereket1144.agentcontroller.run;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RunSchedulerDrainTest {

    @Test
    @DisplayName("R-2 one poll starts every waiting run, not just the first")
    void onePollDispatchesUntilNothingIsLeft() {
        RunDispatcher runDispatcher = mock(RunDispatcher.class);
        // Two runs are waiting; the third attempt finds none.
        when(runDispatcher.dispatchNext()).thenReturn(true, true, false);

        new RunScheduler(runDispatcher).poll();

        verify(runDispatcher, times(3)).dispatchNext();
    }
}
