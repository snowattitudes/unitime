/*
 * UniTime 3.2 (University Timetabling Application)
 * Copyright (C) 2011, UniTime LLC, and individual contributors
 * as indicated by the @authors tag.
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along
 * with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
*/
package org.unitime.timetable.onlinesectioning.solver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import net.sf.cpsolver.ifs.solution.Solution;
import net.sf.cpsolver.ifs.util.DataProperties;
import net.sf.cpsolver.ifs.util.DistanceMetric;
import net.sf.cpsolver.studentsct.StudentSectioningModel;
import net.sf.cpsolver.studentsct.constraint.SectionLimit;
import net.sf.cpsolver.studentsct.extension.DistanceConflict;
import net.sf.cpsolver.studentsct.extension.TimeOverlapsCounter;
import net.sf.cpsolver.studentsct.model.CourseRequest;
import net.sf.cpsolver.studentsct.model.FreeTimeRequest;
import net.sf.cpsolver.studentsct.model.Request;
import net.sf.cpsolver.studentsct.model.Section;
import net.sf.cpsolver.studentsct.model.Student;

import org.unitime.timetable.ApplicationProperties;
import org.unitime.timetable.gwt.server.DayCode;
import org.unitime.timetable.gwt.shared.ClassAssignmentInterface;
import org.unitime.timetable.gwt.shared.CourseRequestInterface;
import org.unitime.timetable.gwt.shared.SectioningException;
import org.unitime.timetable.gwt.shared.SectioningExceptionType;
import org.unitime.timetable.onlinesectioning.OnlineSectioningServer;
import org.unitime.timetable.onlinesectioning.OnlineSectioningHelper;

/**
 * @author Tomas Muller
 */
public class ComputeSuggestionsAction extends FindAssignmentAction {
	private ClassAssignmentInterface.ClassAssignment iSelection;
	
	
	public ComputeSuggestionsAction(CourseRequestInterface request, Collection<ClassAssignmentInterface.ClassAssignment> currentAssignment, ClassAssignmentInterface.ClassAssignment selectedAssignment) throws SectioningException {
		super(request, currentAssignment);
		iSelection = selectedAssignment;
	}
	
	public ClassAssignmentInterface.ClassAssignment getSelection() { return iSelection; }

	@Override
	public List<ClassAssignmentInterface> execute(OnlineSectioningServer server, OnlineSectioningHelper helper) {
		long t0 = System.currentTimeMillis();

		DataProperties config = new DataProperties(ApplicationProperties.getProperties());
		config.setProperty("Neighbour.BranchAndBoundTimeout", "1000");
		config.setProperty("Extensions.Classes", DistanceConflict.class.getName() + ";" + TimeOverlapsCounter.class.getName());
		config.setProperty("StudentWeights.Class", StudentSchedulingAssistantWeights.class.getName());
		config.setProperty("Distances.Ellipsoid", ApplicationProperties.getProperty("unitime.distance.ellipsoid", DistanceMetric.Ellipsoid.LEGACY.name()));
		config.setProperty("Reservation.CanAssignOverTheLimit", "true");
		StudentSectioningModel model = new StudentSectioningModel(config);
		model.addGlobalConstraint(new SectionLimit(model.getProperties()));
		Student student = new Student(getRequest().getStudentId() == null ? -1l : getRequest().getStudentId());
		Student original = (getRequest().getStudentId() == null ? null : server.getStudent(getRequest().getStudentId()));
		for (CourseRequestInterface.Request c: getRequest().getCourses())
			addRequest(server, model, student, original, c, false, true);
		if (student.getRequests().isEmpty()) throw new SectioningException(SectioningExceptionType.EMPTY_COURSE_REQUEST);
		for (CourseRequestInterface.Request c: getRequest().getAlternatives())
			addRequest(server, model, student, original, c, true, true);
		model.addStudent(student);
		model.setDistanceConflict(new DistanceConflict(null, model.getProperties()));
		
		long t1 = System.currentTimeMillis();

		Hashtable<CourseRequest, Set<Section>> preferredSectionsForCourse = new Hashtable<CourseRequest, Set<Section>>();
		Hashtable<CourseRequest, Set<Section>> requiredSectionsForCourse = new Hashtable<CourseRequest, Set<Section>>();
		HashSet<FreeTimeRequest> requiredFreeTimes = new HashSet<FreeTimeRequest>();
        ArrayList<ClassAssignmentInterface> ret = new ArrayList<ClassAssignmentInterface>();
        ClassAssignmentInterface messages = new ClassAssignmentInterface();
        ret.add(messages);

		Request selectedRequest = null;
		Section selectedSection = null;
		for (Iterator<Request> e = student.getRequests().iterator(); e.hasNext();) {
			Request r = (Request)e.next();
			if (r instanceof CourseRequest) {
				CourseRequest cr = (CourseRequest)r;
				if (!getSelection().isFreeTime() && cr.getCourse(getSelection().getCourseId()) != null) {
					selectedRequest = r;
					if (getSelection().getClassId() != null) {
						Section section = cr.getSection(getSelection().getClassId());
						if (section != null) selectedSection = section;
					}
				}
				HashSet<Section> preferredSections = new HashSet<Section>();
				HashSet<Section> requiredSections = new HashSet<Section>();
				a: for (ClassAssignmentInterface.ClassAssignment a: getAssignment()) {
					if (!a.isFreeTime() && cr.getCourse(a.getCourseId()) != null && a.getClassId() != null) {
						Section section = cr.getSection(a.getClassId());
						if (section == null || section.getLimit() == 0) {
							messages.addMessage((a.isSaved() ? "Enrolled class" : a.isPinned() ? "Required class" : "Previously selected class") + a.getSubject() + " " + a.getCourseNbr() + " " + a.getSubpart() + " " + a.getSection() + " is no longer available.");
							continue a;
						}
						if (a.isPinned() || a.isSaved()) 
							requiredSections.add(section);
						preferredSections.add(section);
						cr.getSelectedChoices().add(section.getChoice());
					}
				}
				preferredSectionsForCourse.put(cr, preferredSections);
				requiredSectionsForCourse.put(cr, requiredSections);
			} else {
				FreeTimeRequest ft = (FreeTimeRequest)r;
				if (getSelection().isFreeTime() && ft.getTime() != null &&
					ft.getTime().getStartSlot() == getSelection().getStart() &&
					ft.getTime().getLength() == getSelection().getLength() && 
					ft.getTime().getDayCode() == DayCode.toInt(DayCode.toDayCodes(getSelection().getDays())))
					selectedRequest = r;
				for (ClassAssignmentInterface.ClassAssignment a: getAssignment()) {
					if (a.isFreeTime() && a.isPinned() && ft.getTime() != null &&
						ft.getTime().getStartSlot() == a.getStart() &&
						ft.getTime().getLength() == a.getLength() && 
						ft.getTime().getDayCode() == DayCode.toInt(DayCode.toDayCodes(a.getDays())))
						requiredFreeTimes.add(ft);
				}
			}
		}
		
        new Solution(model);
        
		long t2 = System.currentTimeMillis();
        
        SuggestionsBranchAndBound suggestionBaB = new SuggestionsBranchAndBound(model.getProperties(), student, requiredSectionsForCourse, requiredFreeTimes, preferredSectionsForCourse, selectedRequest, selectedSection);
        TreeSet<SuggestionsBranchAndBound.Suggestion> suggestions = suggestionBaB.computeSuggestions();
        
		long t3 = System.currentTimeMillis();
		helper.debug("  -- suggestion B&B took "+suggestionBaB.getTime()+"ms"+(suggestionBaB.isTimeoutReached()?", timeout reached":""));

		Set<Long> savedClasses = getEnrolledClasses(server, getRequest().getStudentId());
		for (SuggestionsBranchAndBound.Suggestion suggestion : suggestions) {
        	ret.add(convert(server, suggestion.getEnrollments(), requiredSectionsForCourse, requiredFreeTimes, false, model.getDistanceConflict(), savedClasses));
        }
        
		long t4 = System.currentTimeMillis();
		helper.info("Sectioning took "+(t4-t0)+"ms (model "+(t1-t0)+"ms, solver init "+(t2-t1)+"ms, sectioning "+(t3-t2)+"ms, conversion "+(t4-t3)+"ms)");

        return ret;
	}

	@Override
	public String name() {
		return "suggestions";
	}
	
	
	

}
