package core

import (
	"sync/atomic"
	"testing"
)

func TestPreparingOwnerStopClosesExactlyOnce(t *testing.T) {
	var closeCalls atomic.Int32
	owner := newRealVpnPreparationOwner(func() { closeCalls.Add(1) })
	var ownership realVpnPreparationOwnership
	if !ownership.begin(owner) {
		t.Fatal("begin() rejected the first preparation owner")
	}

	stopped := ownership.cancel()
	stopped.cancel()
	stopped.cancel()
	if got := closeCalls.Load(); got != 1 {
		t.Fatalf("close calls = %d, want 1", got)
	}
	if ownership.current != nil {
		t.Fatal("stop left a preparing owner behind")
	}
}

func TestCancelledPreparationCannotPublishAfterNewOwnerStarts(t *testing.T) {
	var closeA atomic.Int32
	ownerA := newRealVpnPreparationOwner(func() { closeA.Add(1) })
	ownerB := newRealVpnPreparationOwner(nil)
	var ownership realVpnPreparationOwnership
	if !ownership.begin(ownerA) {
		t.Fatal("begin(A) failed")
	}

	cancelled := ownership.cancel()
	cancelled.cancel()
	if got := closeA.Load(); got != 1 {
		t.Fatalf("A close calls = %d, want 1", got)
	}
	if !ownership.begin(ownerB) {
		t.Fatal("begin(B) failed after A cancellation")
	}
	if ownership.complete(ownerA) {
		t.Fatal("late completion from A was allowed to publish")
	}
	if !ownership.complete(ownerB) {
		t.Fatal("current owner B was not allowed to publish")
	}
}

func TestTimedOutPreparationCannotLeavePublishableOwner(t *testing.T) {
	owner := newRealVpnPreparationOwner(nil)
	var ownership realVpnPreparationOwnership
	if !ownership.begin(owner) {
		t.Fatal("begin() failed")
	}

	// A timeout uses the same cancellation path as StopRealVpn. The owner is
	// removed before its client is closed, so a late setup return cannot become
	// a prepared client.
	ownership.cancel().cancel()
	if ownership.complete(owner) {
		t.Fatal("timed-out owner was allowed to publish a prepared client")
	}
}
