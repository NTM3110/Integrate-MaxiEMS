package io.openems.edge.bms.service.alarms.implement;

import java.util.List;

/** Pure sample-count based alarm lifecycle state machine. */
public final class AlarmLifecycleStateMachine {

	public enum Status {
		INACTIVE, PENDING_RAISE, ACTIVE, PENDING_CLEAR, LATCHED
	}

	public enum Observation {
		ACTIVE, CLEAR, SUSPENDED
	}

	public enum Transition {
		RAISED, CONDITION_CLEARED, CONDITION_REACTIVATED, CLEARED
	}

	public record State(Status status, int raiseCount, int clearCount, boolean conditionActive) {
		public static State inactive() {
			return new State(Status.INACTIVE, 0, 0, false);
		}
	}

	public record Result(State state, List<Transition> transitions) {
	}

	private AlarmLifecycleStateMachine() {
	}

	public static Result apply(State previous, Observation observation, AlarmLifecyclePolicy.Rule rule) {
		if (observation == Observation.SUSPENDED) return new Result(previous, List.of());
		if (observation == Observation.ACTIVE) {
			return switch (previous.status()) {
			case INACTIVE, PENDING_RAISE -> {
				int count = previous.raiseCount() + 1;
				if (count >= rule.raiseSamples()) {
					yield new Result(new State(Status.ACTIVE, 0, 0, true), List.of(Transition.RAISED));
				}
				yield new Result(new State(Status.PENDING_RAISE, count, 0, true), List.of());
			}
			case ACTIVE, PENDING_CLEAR -> new Result(new State(Status.ACTIVE, 0, 0, true), List.of());
			case LATCHED -> new Result(new State(Status.ACTIVE, 0, 0, true),
					List.of(Transition.CONDITION_REACTIVATED));
			};
		}
		return switch (previous.status()) {
		case INACTIVE -> new Result(State.inactive(), List.of());
		case PENDING_RAISE -> new Result(State.inactive(), List.of());
		case ACTIVE, PENDING_CLEAR -> {
			int count = previous.clearCount() + 1;
			if (count < rule.clearSamples()) {
				yield new Result(new State(Status.PENDING_CLEAR, 0, count, false), List.of());
			}
			if (rule.latching()) {
				yield new Result(new State(Status.LATCHED, 0, 0, false),
						List.of(Transition.CONDITION_CLEARED));
			}
			yield new Result(State.inactive(), List.of(Transition.CONDITION_CLEARED, Transition.CLEARED));
		}
		case LATCHED -> rule.latching()
				? new Result(previous, List.of())
				: new Result(State.inactive(), List.of(Transition.CLEARED));
		};
	}
}
