package datadog.trace.core.scopemanager

import datadog.trace.bootstrap.instrumentation.api.AgentScope
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.NoopAgentScope
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.NoopAgentSpan
import datadog.trace.common.writer.ListWriter
import datadog.trace.context.ScopeListener
import datadog.trace.core.CoreTracer
import datadog.trace.core.DDSpan
import datadog.trace.core.DDSpanContext
import datadog.trace.util.test.DDSpecification
import spock.lang.Shared
import spock.lang.Subject
import spock.lang.Timeout

import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

import static datadog.trace.util.gc.GCUtils.awaitGC
import static java.util.concurrent.TimeUnit.SECONDS

class ScopeManagerTest extends DDSpecification {

  @Shared
  CountDownLatch latch
  @Shared
  ListWriter writer
  @Shared
  CoreTracer tracer

  @Shared
  @Subject
  ContextualScopeManager scopeManager

  def setupSpec() {
    latch = new CountDownLatch(1)
    final currentLatch = latch
    writer = new ListWriter() {
      void incrementTraceCount() {
        currentLatch.countDown()
      }
    }
    tracer = CoreTracer.builder().writer(writer).build()
    scopeManager = tracer.scopeManager
  }

  def cleanup() {
    scopeManager.tlsScope.remove()
    scopeManager.scopeListeners.clear()
    writer.clear()
  }

  def "non-ddspan activation results in a simple scope"() {
    when:
    def scope = scopeManager.activate(NoopAgentSpan.INSTANCE)

    then:
    scopeManager.active() == scope
    scope instanceof SimpleScope

    when:
    scope.close()

    then:
    scopeManager.active() == null
  }

  def "threadlocal is empty"() {
    setup:
    def builder = tracer.buildSpan("test")
    builder.start()

    expect:
    scopeManager.active() == null
    writer.empty
  }

  def "threadlocal is active"() {
    when:
    def span = tracer.buildSpan("test").start()
    def scope = tracer.activateSpan(span)

    then:
    !spanFinished(scope.span())
    scopeManager.active() == scope
    scope instanceof ContinuableScope
    writer.empty

    when:
    scope.close()

    then:
    !spanFinished(scope.span())
    writer == []
    scopeManager.active() == null

    when:
    span.finish()

    then:
    spanFinished(scope.span())
    writer == [[scope.span()]]
    scopeManager.active() == null
  }

  def "sets parent as current upon close"() {
    setup:
    def parentSpan = tracer.buildSpan("parent").start()
    def parentScope = tracer.activateSpan(parentSpan)
    def childSpan = noopChild ? NoopAgentSpan.INSTANCE : tracer.buildSpan("child").start()
    def childScope = tracer.activateSpan(childSpan)

    expect:
    scopeManager.active() == childScope
    noopChild || childScope.span().context().parentId == parentScope.span().context().spanId
    noopChild || childScope.span().context().trace == parentScope.span().context().trace

    when:
    childScope.close()

    then:
    scopeManager.active() == parentScope
    noopChild || !spanFinished(childScope.span())
    !spanFinished(parentScope.span())
    writer == []

    where:
    noopChild | _
    false     | _
    true      | _
  }

  def "scopemanager returns noop scope if depth exceeded"() {
    when: "fill up the scope stack"
    AgentScope scope = null
    for (int i = 0; i <= depth; i++) {
      def span = tracer.buildSpan("test").start()
      scope = tracer.activateSpan(span)
      assert scope instanceof ContinuableScope
    }

    then: "last scope is still valid"
    (scope as ContinuableScope).depth() == depth

    when: "activate a scope over the limit"
    scope = scopeManager.activate(NoopAgentSpan.INSTANCE)

    then: "a noop instance is returned"
    scope instanceof NoopAgentScope

    when: "try again for good measure"
    scope = scopeManager.activate(NoopAgentSpan.INSTANCE)

    then: "still have a noop instance"
    scope instanceof NoopAgentScope

    and: "scope stack not effected."
    (scopeManager.active() as ContinuableScope).depth() == depth

    where:
    depth = scopeManager.depthLimit
  }

  def "ContinuableScope only creates continuations when propagation is set"() {
    setup:
    def span = tracer.buildSpan("test").start()
    def scope = (ContinuableScope) tracer.activateSpan(span)
    def continuation = scope.capture()

    expect:
    continuation == null

    when:
    scope.setAsyncPropagation(true)
    continuation = scope.capture()
    then:
    continuation != null

    cleanup:
    continuation.cancel()
  }

  def "Continuation.cancel doesn't close parent scope"() {
    setup:
    def span = tracer.buildSpan("test").start()
    def scope = (ContinuableScope) tracer.activateSpan(span)
    scope.setAsyncPropagation(true)
    def continuation = scope.capture()

    when:
    continuation.cancel()

    then:
    scopeManager.active() == scope
  }

  def "ContinuableScope doesn't close if non-zero"() {
    setup:
    def builder = tracer.buildSpan("test")
    def scope = (ContinuableScope) builder.startActive(true)
    scope.setAsyncPropagation(true)
    def continuation = scope.capture()

    expect:
    !spanFinished(scope.span())
    scopeManager.active() == scope
    scope instanceof ContinuableScope
    writer.empty

    when:
    scope.close()

    then:
    !spanFinished(scope.span())
    scopeManager.active() == null
    writer.empty

    when:
    continuation.activate()
    if (forceGC) {
      continuation = null // Continuation references also hold up traces.
      awaitGC() // The goal here is to make sure that continuation DOES NOT get GCed
      while (((DDSpanContext) scope.span().context()).trace.clean()) {
      }
    }
    if (autoClose) {
      if (continuation != null) {
        continuation.close()
      }
    }

    then:
    scopeManager.active() != null

    when:
    scopeManager.active().close()
    writer.waitForTraces(1)

    then:
    scopeManager.active() == null
    spanFinished(scope.span())
    writer == [[scope.span()]]

    where:
    autoClose | forceGC
    true      | true
    true      | false
    false     | true
  }

  def "Continuation.close closes parent scope"() {
    setup:
    def builder = tracer.buildSpan("test")
    def scope = (ContinuableScope) builder.startActive(true)
    scope.setAsyncPropagation(true)
    def continuation = scope.capture()

    when:
    /*
    Note: this API is inherently broken. Our scope implementation doesn't allow us to close scopes
    in random order, yet when we close continuation we attempt to close scope by default.
    And in fact continuation trying to close parent scope is most likely a bug.
     */
    continuation.close(true)

    then:
    scopeManager.active() == null
    !spanFinished(scope.span())

    when:
    scope.span().finish()

    then:
    scopeManager.active() == null
  }

  def "Continuation.close doesn't close parent scope"() {
    setup:
    def builder = tracer.buildSpan("test")
    def scope = (ContinuableScope) builder.startActive(true)
    scope.setAsyncPropagation(true)
    def continuation = scope.capture()

    when:
    continuation.close(false)

    then:
    scopeManager.active() == scope
  }

  def "Continuation.close doesn't close parent scope, span finishes"() {
    /*
    This is highly confusing behaviour. Sequence of events is as following:
      * Scope gets created along with span and with finishOnClose == true.
      * Continuation gets created for that scope.
      * Scope is closed.
        At this point scope is not really closed. It is removed from scope
        stack, but it is still alive because there is a live continuation attached
        to it. This also means span is not closed.
      * Continuation is closed.
        This triggers final closing of scope and closing of the span.

     This is confusing because expected behaviour is for span to be closed
     with the scope when finishOnClose = true, but in fact span lingers until
     continuation is closed.
     */
    setup:
    def builder = tracer.buildSpan("test")
    def scope = (ContinuableScope) builder.startActive(true)
    scope.setAsyncPropagation(true)
    def continuation = scope.capture()
    scope.close()

    when:
    continuation.close(false)

    then:
    scopeManager.active() == null
    spanFinished(scope.span())
    writer == [[scope.span()]]
  }

  @Timeout(value = 60, unit = SECONDS)
  def "hard reference on continuation prevents trace from reporting"() {
    setup:
    def span = tracer.buildSpan("test").start()
    def scope = (ContinuableScope) tracer.activateSpan(span)
    scope.setAsyncPropagation(true)
    def continuation = scope.capture()
    scope.close()
    span.finish()

    expect:
    scopeManager.active() == null
    spanFinished(span)
    writer == []

    when:
    if (forceGC) {
      def continuationRef = new WeakReference<>(continuation)
      continuation = null // Continuation references also hold up traces.
      awaitGC(continuationRef)
      latch.await(60, SECONDS)
    }
    if (autoClose) {
      if (continuation != null) {
        continuation.cancel()
      }
    }

    then:
    forceGC ? true : writer == [[span]]

    where:
    autoClose | forceGC
    true      | true
    true      | false
    false     | true
  }

  def "continuation restores trace"() {
    setup:
    def parentSpan = tracer.buildSpan("parent").start()
    def parentScope = tracer.activateSpan(parentSpan)
    def childSpan = tracer.buildSpan("child").start()
    ContinuableScope childScope = (ContinuableScope) tracer.activateSpan(childSpan)
    childScope.setAsyncPropagation(true)

    def continuation = childScope.capture()
    childScope.close()

    expect:
    parentSpan.context().trace == childSpan.context().trace
    scopeManager.active() == parentScope
    !spanFinished(childSpan)
    !spanFinished(parentSpan)

    when:
    parentScope.close()
    parentSpan.finish()
    // parent span is finished, but trace is not reported

    then:
    scopeManager.active() == null
    !spanFinished(childSpan)
    spanFinished(parentSpan)
    writer == []

    when:
    def newScope = continuation.activate()
    newScope.setAsyncPropagation(true)
    def newContinuation = newScope.capture()

    then:
    newScope instanceof ContinuableScope
    scopeManager.active() == newScope
    newScope != childScope && newScope != parentScope
    newScope.span() == childSpan
    !spanFinished(childSpan)
    spanFinished(parentSpan)
    writer == []

    when:
    newScope.close()
    newContinuation.activate().close()
    childSpan.finish()

    then:
    scopeManager.active() == null
    spanFinished(childSpan)
    spanFinished(parentSpan)
    writer == [[childSpan, parentSpan]]
  }

  def "continuation allows adding spans even after other spans were completed"() {
    setup:
    def span = tracer.buildSpan("test").start()
    def scope = (ContinuableScope) tracer.activateSpan(span)
    scope.setAsyncPropagation(true)
    def continuation = scope.capture()
    scope.close()
    span.finish()

    def newScope = continuation.activate()

    expect:
    newScope instanceof ContinuableScope
    newScope != scope
    scopeManager.active() == newScope
    spanFinished(span)
    writer == []

    when:
    def childSpan = tracer.buildSpan("child").start()
    def childScope = tracer.activateSpan(childSpan)
    childScope.close()
    childSpan.finish()
    scopeManager.active().close()

    then:
    scopeManager.active() == null
    spanFinished(childSpan)
    childSpan.context().parentId == span.context().spanId
    writer == [[childSpan, span]]
  }

  def "ContinuableScope put in threadLocal after continuation activation"() {
    setup:
    def span = tracer.buildSpan("parent").start()
    ContinuableScope scope = (ContinuableScope) tracer.activateSpan(span)
    scope.setAsyncPropagation(true)

    expect:
    scopeManager.tlsScope.get() == scope

    when:
    def cont = scope.capture()
    scope.close()

    then:
    scopeManager.tlsScope.get() == null

    when:
    def newScope = cont.activate()

    then:
    newScope != scope
    scopeManager.tlsScope.get() == newScope
  }

  def "add scope listener"() {
    setup:
    AtomicInteger activatedCount = new AtomicInteger(0)
    AtomicInteger closedCount = new AtomicInteger(0)

    scopeManager.addScopeListener(new ScopeListener() {
      @Override
      void afterScopeActivated() {
        activatedCount.incrementAndGet()
      }

      @Override
      void afterScopeClosed() {
        closedCount.incrementAndGet()
      }
    })

    when:
    AgentScope scope1 = scopeManager.activate(NoopAgentSpan.INSTANCE)

    then:
    activatedCount.get() == 1
    closedCount.get() == 0

    when:
    AgentScope scope2 = scopeManager.activate(NoopAgentSpan.INSTANCE)

    then: 'Activating the same span multiple times does not create a new scope'
    activatedCount.get() == 1
    closedCount.get() == 0

    when:
    scope2.close()

    then: 'Closing a scope once that has been activated multiple times does not close'
    activatedCount.get() == 1
    closedCount.get() == 0

    when:
    scope1.close()

    then:
    activatedCount.get() == 1
    closedCount.get() == 1

    when:
    AgentSpan span = tracer.buildSpan("foo").start()
    AgentScope continuableScope = tracer.activateSpan(span)

    then:
    continuableScope instanceof ContinuableScope
    activatedCount.get() == 2

    when:
    AgentSpan childSpan = tracer.buildSpan("foo").start()
    AgentScope childContinuableScope = tracer.activateSpan(childSpan)

    then:
    childContinuableScope instanceof ContinuableScope
    activatedCount.get() == 3
    closedCount.get() == 1

    when:
    childContinuableScope.close()
    childSpan.finish()

    then:
    activatedCount.get() == 4
    closedCount.get() == 2

    when:
    continuableScope.close()
    span.finish()

    then:
    activatedCount.get() == 4 // the last scope closed was the last one on the stack so nothing more got activated
    closedCount.get() == 3
  }

  boolean spanFinished(AgentSpan span) {
    return ((DDSpan) span)?.isFinished()
  }
}
