package org.openmrs.module.htmlformentry.element;

import org.apache.commons.lang.StringUtils;
import org.openmrs.LocationTag;
import org.openmrs.Patient;
import org.openmrs.Program;
import org.openmrs.ProgramWorkflowState;
import org.openmrs.module.htmlformentry.FormEntryContext;
import org.openmrs.module.htmlformentry.FormEntryContext.Mode;
import org.openmrs.module.htmlformentry.FormEntryException;
import org.openmrs.module.htmlformentry.FormEntrySession;
import org.openmrs.module.htmlformentry.FormSubmissionError;
import org.openmrs.module.htmlformentry.HtmlFormEntryUtil;
import org.openmrs.module.htmlformentry.action.FormSubmissionControllerAction;
import org.openmrs.module.htmlformentry.widget.CheckboxWidget;
import org.openmrs.module.htmlformentry.widget.DateWidget;
import org.openmrs.module.htmlformentry.widget.ErrorWidget;
import org.openmrs.module.htmlformentry.widget.ToggleWidget;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Serves as both the HtmlGeneratorElement and the FormSubmissionControllerAction for a Program
 * Enrollment.
 */
public class EnrollInProgramElement implements HtmlGeneratorElement, FormSubmissionControllerAction {
	
	private Program program;
	
	private List<ProgramWorkflowState> states;
	
	private CheckboxWidget checkToEnrollWidget;
	
	private ErrorWidget checkToEnrollErrorWidget;
	
	private DateWidget dateWidget;
	
	private ErrorWidget dateErrorWidget;
	
	private LocationTag locationTag;
	
	public EnrollInProgramElement(FormEntryContext context, Map<String, String> parameters) {
		try {
			program = HtmlFormEntryUtil.getProgram(parameters.get("programId"));
			if (program == null)
				throw new FormEntryException("");
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Couldn't find program in: " + parameters);
		}
		
		if ("true".equalsIgnoreCase(parameters.get("showDate"))) {
			dateWidget = new DateWidget();
			dateErrorWidget = new ErrorWidget();
			context.registerWidget(dateWidget);
			context.registerErrorWidget(dateWidget, dateErrorWidget);
		}
		
		if ("true".equalsIgnoreCase(parameters.get("showCheckbox"))) {
			checkToEnrollWidget = new CheckboxWidget();
			{ // If patient is already enrolled, check and disable the checkbox
				Patient patient = context.getExistingPatient();
				Date encounterDate = context.getPreviousEncounterDate();
				if (encounterDate == null) {
					encounterDate = context.getBestApproximationOfEncounterDate();
				}
				if (HtmlFormEntryUtil.isEnrolledInProgramOnDate(patient, program, encounterDate)) {
					checkToEnrollWidget.setInitialValue("true");
					checkToEnrollWidget.setDisabled(true);
				}
			}
			context.registerWidget(checkToEnrollWidget);
			checkToEnrollErrorWidget = new ErrorWidget();
			context.registerErrorWidget(checkToEnrollWidget, checkToEnrollErrorWidget);
		}
		
		String toggleParameter = parameters.get("toggle");
		if (toggleParameter != null) {
			if (checkToEnrollWidget == null) {
				throw new RuntimeException("<enrollInProgram> 'toggle' parameter requires 'showCheckbox=\"true\"'");
			}
			ToggleWidget toggleWidget = new ToggleWidget(toggleParameter);
			checkToEnrollWidget.setToggleTarget(toggleWidget.getTargetId());
			checkToEnrollWidget.setToggleDimInd(toggleWidget.isToggleDim());
		}
		
		String stateIdsStr = parameters.get("stateIds");
		if (StringUtils.isNotBlank(stateIdsStr)) {
			states = new ArrayList<ProgramWorkflowState>();
			String[] stateIdsUuidsOrPrefNames = stateIdsStr.split(",");
			//set to store unique work flow state combinations so as to determine multiple states in same work flow
			Set<String> workflowsAndStates = new HashSet<String>();
			for (String value : stateIdsUuidsOrPrefNames) {
				value = value.trim();
				ProgramWorkflowState state = HtmlFormEntryUtil.getState(value, program);
				if (state == null) {
					String errorMsgPart = "with an id or uuid";
					if (value.indexOf(":") > -1)
						errorMsgPart = "associated to a concept with a concept mapping";
					throw new FormEntryException(
					        "Cannot find a program work flow state " + errorMsgPart + " that matches '" + value + "'");
				} else if (!state.getInitial()) {
					throw new FormEntryException(
					        "The program work flow state that matches '" + value + "' is not marked as initial");
				} else if (!workflowsAndStates.add(state.getProgramWorkflow().getUuid())) {
					throw new FormEntryException("A patient cannot be in multiple states in the same workflow");
				}
				if (!states.contains(state))
					states.add(state);
			}
		}
		
		String locationTagStr = parameters.get("locationTag");
		if (StringUtils.isNotBlank(locationTagStr)) {
			locationTag = HtmlFormEntryUtil.getLocationTag(locationTagStr);
			if (locationTag == null) {
				throw new FormEntryException("Unable to find location tag " + locationTagStr);
			}
		}
	}
	
	/**
	 * @see org.openmrs.module.htmlformentry.element.HtmlGeneratorElement#generateHtml(org.openmrs.module.htmlformentry.FormEntryContext)
	 */
	@Override
	public String generateHtml(FormEntryContext context) {
		StringBuilder sb = new StringBuilder();
		if (dateWidget != null) {
			sb.append(dateWidget.generateHtml(context));
			if (context.getMode() != Mode.VIEW)
				sb.append(dateErrorWidget.generateHtml(context));
		}
		if (checkToEnrollWidget != null && context.getMode() != Mode.VIEW) {
			sb.append(checkToEnrollWidget.generateHtml(context));
		}
		return sb.toString();
	}
	
	/**
	 * @see org.openmrs.module.htmlformentry.action.FormSubmissionControllerAction#handleSubmission(org.openmrs.module.htmlformentry.FormEntrySession,
	 *      javax.servlet.http.HttpServletRequest)
	 */
	@Override
	public void handleSubmission(FormEntrySession session, HttpServletRequest submission) {
		// Only enroll if we are not in view mode and either the checkbox is checked or it doesn't exist
		if (session.getContext().getMode() != Mode.VIEW && (checkToEnrollWidget == null
		        || "true".equals(checkToEnrollWidget.getValue(session.getContext(), submission)))) {
			Date selectedDate = null;
			if (dateWidget != null) {
				selectedDate = (Date) dateWidget.getValue(session.getContext(), submission);
			}
			session.getSubmissionActions().enrollInProgram(program, selectedDate, states, locationTag);
		}
	}
	
	/**
	 * @see org.openmrs.module.htmlformentry.action.FormSubmissionControllerAction#validateSubmission(org.openmrs.module.htmlformentry.FormEntryContext,
	 *      javax.servlet.http.HttpServletRequest)
	 */
	@Override
	public Collection<FormSubmissionError> validateSubmission(FormEntryContext context, HttpServletRequest submission) {
		return Collections.emptySet();
	}
	
}
