package io.flutter.vmService;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.concurrency.Semaphore;
import com.intellij.xdebugger.XExpression;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.frame.XSuspendContext;
import com.intellij.xdebugger.frame.XValue;
import com.jetbrains.lang.dart.ide.runner.DartExceptionBreakpointProperties;
import io.flutter.vmService.frame.DartVmServiceSuspendContext;
import io.flutter.vmService.frame.DartVmServiceValue;
import org.dartlang.vm.service.VmServiceListener;
import org.dartlang.vm.service.element.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class DartVmServiceListener implements VmServiceListener {
  private static final @NotNull Logger LOG = Logger.getInstance(DartVmServiceListener.class.getName());

  @NotNull private final DartVmServiceDebugProcess myDebugProcess;
  @NotNull private final DartVmServiceBreakpointHandler myBreakpointHandler;
  @Nullable private XSourcePosition myLatestSourcePosition;

  public DartVmServiceListener(@NotNull final DartVmServiceDebugProcess debugProcess,
                               @NotNull final DartVmServiceBreakpointHandler breakpointHandler) {
    myDebugProcess = debugProcess;
    myBreakpointHandler = breakpointHandler;
  }

  @Override
  public void connectionOpened() {

  }

  @Override
  public void received(@NotNull final String streamId, @NotNull final Event event) {
    switch (event.getKind()) {
      case BreakpointAdded:
        // TODO Respond to breakpoints added by the observatory.
        // myBreakpointHandler.vmBreakpointAdded(null, event.getIsolate().getId(), event.getBreakpoint());
        break;
      case BreakpointRemoved:
        break;
      case BreakpointResolved:
        myBreakpointHandler.breakpointResolved(event.getBreakpoint());
        break;
      case Extension:
        break;
      case GC:
        break;
      case Inspect:
        break;
      case IsolateStart:
        break;
      case IsolateRunnable:
        myDebugProcess.getVmServiceWrapper().handleIsolate(event.getIsolate(), false);
        break;
      case IsolateReload:
        break;
      case IsolateUpdate:
        break;
      case IsolateExit:
        myDebugProcess.isolateExit(event.getIsolate());
        break;
      case PauseBreakpoint:
      case PauseException:
      case PauseInterrupted:
        myDebugProcess.isolateSuspended(event.getIsolate());

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
          final ElementList<Breakpoint> breakpoints = event.getKind() == EventKind.PauseBreakpoint ? event.getPauseBreakpoints() : null;
          final InstanceRef exception = event.getKind() == EventKind.PauseException ? event.getException() : null;
          onIsolatePaused(event.getIsolate(), breakpoints, exception, event.getTopFrame(), event.getAtAsyncSuspension());
        });
        break;
      case PausePostRequest:
        // We get this event after an isolate reload call, when pause after reload has been requested.
        // This adds the "supports.pausePostRequest" capability.
        myDebugProcess.getVmServiceWrapper().restoreBreakpointsForIsolate(event.getIsolate().getId(),
                                                                          () -> myDebugProcess.getVmServiceWrapper()
                                                                            .resumeIsolate(event.getIsolate().getId(), null));
        break;
      case PauseExit:
        break;
      case PauseStart:
        myDebugProcess.getVmServiceWrapper().handleIsolate(event.getIsolate(), true);
        break;
      case Resume:
        myDebugProcess.isolateResumed(event.getIsolate());
        break;
      case ServiceExtensionAdded:
        break;
      case ServiceRegistered:
        break;
      case ServiceUnregistered:
        break;
      case VMUpdate:
        break;
      case WriteEvent:
        myDebugProcess.handleWriteEvent(event.getBytes());
        break;
      case None:
        break;
      case Unknown:
        break;
    }
  }

  @Override
  public void connectionClosed() {
    myDebugProcess.getSession().stop();
  }

  void onIsolatePaused(@NotNull final IsolateRef isolateRef,
                       @Nullable final ElementList<Breakpoint> vmBreakpoints,
                       @Nullable final InstanceRef exception,
                       @Nullable final Frame vmTopFrame,
                       boolean atAsyncSuspension) {
    if (vmTopFrame == null) {
      myDebugProcess.getSession().positionReached(new XSuspendContext() {
      });
      return;
    }

    final DartVmServiceSuspendContext suspendContext =
      new DartVmServiceSuspendContext(myDebugProcess, isolateRef, vmTopFrame, exception, atAsyncSuspension);
    final XStackFrame xTopFrame = suspendContext.getActiveExecutionStack().getTopFrame();
    final XSourcePosition sourcePosition = xTopFrame == null ? null : xTopFrame.getSourcePosition();

    if (vmBreakpoints == null || vmBreakpoints.isEmpty()) {
      final StepOption latestStep = myDebugProcess.getVmServiceWrapper().getLatestStep();

      if (latestStep == StepOption.Over && equalSourcePositions(myLatestSourcePosition, sourcePosition)) {
        final StepOption nextStep = atAsyncSuspension ? StepOption.OverAsyncSuspension : latestStep;
        // continue stepping to change current line
        myDebugProcess.getVmServiceWrapper().resumeIsolate(isolateRef.getId(), nextStep);
      }
      else if (exception != null) {
        final XBreakpoint<DartExceptionBreakpointProperties> breakpoint =
          DartExceptionBreakpointHandler.getDefaultExceptionBreakpoint(myDebugProcess.getSession().getProject());
        final boolean suspend = myDebugProcess.getSession().breakpointReached(breakpoint, null, suspendContext);
        if (!suspend) {
          myDebugProcess.getVmServiceWrapper().resumeIsolate(isolateRef.getId(), null);
        }
      }
      else {
        myLatestSourcePosition = sourcePosition;
        myDebugProcess.getSession().positionReached(suspendContext);
      }
    }
    else {
      if (vmBreakpoints.size() > 1) {
        // Shouldn't happen. IDE doesn't allow to set 2 breakpoints on one line.
        LOG.warn(vmBreakpoints.size() + " breakpoints hit in one shot.");
      }

      // Remove any temporary (run to cursor) breakpoints.
      myBreakpointHandler.removeTemporaryBreakpoints(isolateRef.getId());

      final XLineBreakpoint<XBreakpointProperties> xBreakpoint = myBreakpointHandler.getXBreakpoint(vmBreakpoints.get(0));

      if (xBreakpoint == null) {
        // breakpoint could be set in the Observatory
        myLatestSourcePosition = sourcePosition;
        myDebugProcess.getSession().positionReached(suspendContext);
        return;
      }

      if ("false".equals(evaluateExpression(isolateRef.getId(), vmTopFrame, xBreakpoint.getConditionExpression()))) {
        myDebugProcess.getVmServiceWrapper().resumeIsolate(isolateRef.getId(), null);
        return;
      }

      myLatestSourcePosition = sourcePosition;

      final String logExpression = evaluateExpression(isolateRef.getId(), vmTopFrame, xBreakpoint.getLogExpressionObject());
      final boolean suspend = myDebugProcess.getSession().breakpointReached(xBreakpoint, logExpression, suspendContext);
      if (!suspend) {
        myDebugProcess.getVmServiceWrapper().resumeIsolate(isolateRef.getId(), null);
      }
    }
  }

  private static boolean equalSourcePositions(@Nullable final XSourcePosition position1, @Nullable final XSourcePosition position2) {
    return position1 != null &&
           position2 != null &&
           position1.getFile().equals(position2.getFile()) &&
           position1.getLine() == position2.getLine();
  }


  @Nullable
  private String evaluateExpression(final @NotNull String isolateId,
                                    final @Nullable Frame vmTopFrame,
                                    final @Nullable XExpression xExpression) {
    final String evalText = xExpression == null ? null : xExpression.getExpression();
    if (vmTopFrame == null || StringUtil.isEmptyOrSpaces(evalText)) return null;

    final Ref<String> evalResult = new Ref<>();
    final Semaphore semaphore = new Semaphore();
    semaphore.down();

    myDebugProcess.getVmServiceWrapper().evaluateInFrame(isolateId, vmTopFrame, evalText, new XDebuggerEvaluator.XEvaluationCallback() {
      @Override
      public void evaluated(@NotNull final XValue result) {
        if (result instanceof DartVmServiceValue) {
          evalResult.set(getSimpleStringPresentation(((DartVmServiceValue)result).getInstanceRef()));
        }
        semaphore.up();
      }

      @Override
      public void errorOccurred(@NotNull final String errorMessage) {
        evalResult.set("Failed to evaluate log expression [" + evalText + "]: " + errorMessage);
        semaphore.up();
      }
    });

    semaphore.waitFor(1000);
    return evalResult.get();
  }

  @NotNull
  private static String getSimpleStringPresentation(@NotNull final InstanceRef instanceRef) {
    // getValueAsString() is provided for the instance kinds: Null, Bool, Double, Int, String (value may be truncated), Float32x4, Float64x2, Int32x4, StackTrace
    return switch (instanceRef.getKind()) {
      case Null, Bool, Double, Int, String, Float32x4, Float64x2, Int32x4, StackTrace -> instanceRef.getValueAsString();
      default -> "Instance of " + instanceRef.getClassRef().getName();
    };
  }
}
