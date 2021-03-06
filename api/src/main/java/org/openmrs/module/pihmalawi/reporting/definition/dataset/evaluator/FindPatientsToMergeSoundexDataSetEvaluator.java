package org.openmrs.module.pihmalawi.reporting.definition.dataset.evaluator;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Cohort;
import org.openmrs.Encounter;
import org.openmrs.EncounterType;
import org.openmrs.Patient;
import org.openmrs.PatientState;
import org.openmrs.ProgramWorkflow;
import org.openmrs.annotation.Handler;
import org.openmrs.api.context.Context;
import org.openmrs.module.pihmalawi.common.ProgramHelper;
import org.openmrs.module.pihmalawi.reporting.definition.dataset.definition.FindPatientsToMergeSoundexDataSetDefinition;
import org.openmrs.module.reporting.cohort.CohortUtil;
import org.openmrs.module.reporting.cohort.query.service.CohortQueryService;
import org.openmrs.module.reporting.common.ObjectUtil;
import org.openmrs.module.reporting.dataset.DataSet;
import org.openmrs.module.reporting.dataset.DataSetColumn;
import org.openmrs.module.reporting.dataset.DataSetRow;
import org.openmrs.module.reporting.dataset.SimpleDataSet;
import org.openmrs.module.reporting.dataset.definition.DataSetDefinition;
import org.openmrs.module.reporting.dataset.definition.evaluator.DataSetEvaluator;
import org.openmrs.module.reporting.evaluation.EvaluationContext;
import org.openmrs.module.reporting.evaluation.querybuilder.SqlQueryBuilder;
import org.openmrs.module.reporting.evaluation.service.EvaluationService;

import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Handler(supports = { FindPatientsToMergeSoundexDataSetDefinition.class })
public class FindPatientsToMergeSoundexDataSetEvaluator implements DataSetEvaluator {

	private final static String OPENMRS_SERVER = "http://emr:8080";

	protected Log log = LogFactory.getLog(this.getClass());

	public DataSet evaluate(DataSetDefinition dataSetDefinition, EvaluationContext context) {
		FindPatientsToMergeSoundexDataSetDefinition dsds = (FindPatientsToMergeSoundexDataSetDefinition) dataSetDefinition;
		SimpleDataSet dataSet = new SimpleDataSet(dataSetDefinition, context);

		context = ObjectUtil.nvl(context, new EvaluationContext());
		Cohort cohort = context.getBaseCohort();

		// By default, get all patients
		if (cohort == null) {
			cohort = Context.getPatientSetService().getAllPatients();
		}

		if (context.getLimit() != null) {
			CohortUtil.limitCohort(cohort, context.getLimit());
		}

        StringBuilder sb = new StringBuilder();
        sb.append(" select pn2.person_id, person2.gender ");
        sb.append(" from person_name_code p1, person_name_code p2, person_name pn1, person_name pn2, person person2, patient patient2 ");
        sb.append(" where pn1.person_id <> pn2.person_id and ");
        sb.append(" and pn2.person_id = person2.person_id"); // This allows us to get gender in sql, rather than getting the full object later
        sb.append(" and person2.person_id = patient2.person_id"); // This allows us to ensure the person is a patient
        if (dsds.isSwapFirstLastName()) {
            sb.append("p1.family_name_code = p2.given_name_code and p1.given_name_code = p2.family_name_code ");
        } else {
            sb.append("p1.given_name_code = p2.given_name_code and p1.family_name_code = p2.family_name_code ");
        }
        sb.append(" and pn1.person_id not in (select user_id from users) and pn2.person_id not in (select user_id from users) ");
        sb.append(" and pn2.person_id in (:personIds)");
        sb.append(" and pn1.person_name_id=p1.person_name_id and pn2.person_name_id=p2.person_name_id and pn1.person_id=:referenceId ;");
        String query = sb.toString();

		Set<Integer> memberIds = cohort.getMemberIds();
		if (dsds.getEncounterTypesToLookForDuplicates() != null) {

			CohortQueryService cqs = Context.getService(CohortQueryService.class);
			memberIds = cqs.getPatientsHavingEncounters(null, null, null, dsds.getEncounterTypesToLookForDuplicates(), null, null, null).getMemberIds();
			Set<Integer> memberIdsWithoutIdentifierType = new HashSet<Integer>();
			
			if (dsds.getPatientIdentifierTypeRequiredToLookForDuplicates() != null) {
				for (Integer id : memberIds) {
					if (Context.getPatientService().getPatient(id).getPatientIdentifier(dsds.getPatientIdentifierTypeRequiredToLookForDuplicates()) == null) {
						memberIdsWithoutIdentifierType.add(id);
					}
				}
				// mkae sure to exclude all patients without any hcc number
				memberIds.removeAll(memberIdsWithoutIdentifierType);
			}
			// make sure the sets are disjunct
			memberIds.removeAll(cohort.getMemberIds());
		}

		List<Patient> patients = Context.getPatientSetService().getPatients(cohort.getMemberIds());

		for (Patient p : patients) {
			DataSetRow row = new DataSetRow();
			DataSetColumn col;
			try {
				Collection<Patient> ps = soundexMatches(query, p, memberIds);
				if (!ps.isEmpty()) {
					col = new DataSetColumn("#", "#", String.class);
					row.addColumnValue(col, linkifyId(p, dsds.getEncounterTypesForSummary(), dsds.getProgramWorkflowForSummary()));
					int i = 1;
					for (Patient potential : ps) {
						col = new DataSetColumn("potential match_" + i, "potential match_" + i, String.class);
						row.addColumnValue(col, linkifyMerge(p, potential, dsds.getEncounterTypesForSummary(), dsds.getProgramWorkflowForSummary()));
						i++;
					}
					dataSet.addRow(row);
				}
			}
            catch (Throwable t) {
				col = new DataSetColumn("Error", "Error", String.class);
				row.addColumnValue(col, "Error while loading patient " + p.getId());
				dataSet.addRow(row);
			}
		}

		return dataSet;
	}

	private Collection<Patient> soundexMatches(String query, Patient referencePatient, Set<Integer> cohortOfPatients) {
        Map<Integer, Patient> potentialDuplicates = new HashMap<Integer, Patient>();

        SqlQueryBuilder qb = new SqlQueryBuilder(query);
        qb.addParameter("referenceId", referencePatient.getId());
        qb.addParameter("personIds", cohortOfPatients);

        EvaluationService evaluationService = Context.getService(EvaluationService.class);
        Map<Integer, String> m = evaluationService.evaluateToMap(qb, Integer.class, String.class, new EvaluationContext());

        for (Integer pId : m.keySet()) {
            String gender = m.get(pId);
            String referenceGender= referencePatient.getGender();
            if (StringUtils.isEmpty(gender) || StringUtils.isEmpty(referenceGender) || gender.equalsIgnoreCase(referenceGender)) {
                potentialDuplicates.put(pId, Context.getPatientService().getPatient(pId));
            }
        }

		return potentialDuplicates.values();
	}

	private String linkifyId(Patient p, List<EncounterType> encounterTypes, ProgramWorkflow pw) {
		return "<a href=" + OPENMRS_SERVER
				+ "/openmrs/patientDashboard.form?patientId=" + p.getId() + ">"
				+ p.getGivenName() + " " + p.getFamilyName() + "</a>"
				+ "<br/>" + p.getGender() + ", " + p.getAge() + ", " + currentVillage(p) 
				+ "<br/>" + firstLastEncounter(p, encounterTypes)
				+ "<br/>" + currentOutcome(p, pw);
	}

	private String currentVillage(Patient p) {
		return p.getAddresses().iterator().next().getCityVillage();
	}

	private String linkifyMerge(Patient p, Patient p2, List<EncounterType> encounterTypes, ProgramWorkflow pw) {
		return "<a href=" + OPENMRS_SERVER
				+ "/openmrs/admin/patients/mergePatients.form?patientId="
				+ p2.getId() + "&patientId=" + p.getId() + ">"
				+ p2.getGivenName() + " " + p2.getFamilyName() + "</a>"
				+ "<br/>" + p2.getGender() + ", " + p2.getAge() + ", " + currentVillage(p2) 
				+ "<br/>" + firstLastEncounter(p2, encounterTypes)
				+ "<br/>" + currentOutcome(p2, pw);
	}

	private String currentOutcome(Patient p, ProgramWorkflow pw) {
		PatientState ps = new ProgramHelper().getMostRecentStateAtDate(p, pw, new Date());
		if (ps != null) {
			return ps.getState().getConcept().getName() + "@" + (ps.getEndDate() == null ? formatDate(ps.getStartDate()) : formatDate(ps.getEndDate())); 
		}
		return null;
	}

	private String firstLastEncounter(Patient p, List<EncounterType> encounterTypes) {
		String e = "";
		List<Encounter> encounters = Context.getEncounterService().getEncounters(p, null, null, null, null, encounterTypes, null, null, null, false);
		if (!encounters.isEmpty()) {
			Encounter firstEncounter = encounters.get(0);
			e = firstEncounter.getEncounterType().getName() + "@" + formatDate(firstEncounter.getEncounterDatetime());
			Encounter lastEncounter = encounters.get(encounters.size() - 1);
			e += " - " + lastEncounter.getEncounterType().getName() + "@" + formatDate(lastEncounter.getEncounterDatetime());			
		}
		return e;
	}

	private String formatDate(Date encounterDatetime) {
		return new SimpleDateFormat("dd-MMM-yyyy").format(encounterDatetime);
	}
}