package org.acme.employeescheduling.rest;

import java.time.LocalDate;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import ai.timefold.solver.core.api.score.buildin.hardsoft.HardSoftScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import ai.timefold.solver.core.api.solver.SolverManager;
import ai.timefold.solver.core.api.solver.SolverStatus;

import org.acme.employeescheduling.bootstrap.DemoDataGenerator;
import org.acme.employeescheduling.domain.EmployeeSchedule;
import org.acme.employeescheduling.domain.ScheduleState;
import org.acme.employeescheduling.domain.Shift;
import org.acme.employeescheduling.persistence.AvailabilityRepository;
import org.acme.employeescheduling.persistence.EmployeeRepository;
import org.acme.employeescheduling.persistence.ScheduleStateRepository;
import org.acme.employeescheduling.persistence.ShiftRepository;
import org.acme.employeescheduling.rest.exception.ScheduleSolverException;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.util.internal.ThreadLocalRandom;
import io.quarkus.panache.common.Sort;

@Path("/schedule")
public class EmployeeScheduleResource {
    private static final Logger LOGGER = LoggerFactory.getLogger(EmployeeScheduleResource.class);

    public static final Long SINGLETON_SCHEDULE_ID = 1L;
    private final ConcurrentMap<Long, Job> jobIdToJob = new ConcurrentHashMap<>();

    @Inject
    AvailabilityRepository availabilityRepository;
    @Inject
    EmployeeRepository employeeRepository;
    @Inject
    ShiftRepository shiftRepository;
    @Inject
    ScheduleStateRepository scheduleStateRepository;

    @Inject
    DemoDataGenerator dataGenerator;

    @Inject
    SolverManager<EmployeeSchedule, Long> solverManager;
    @Inject
    SolutionManager<EmployeeSchedule, HardSoftScore> solutionManager;

    // Workaround to make Quarkus CDI happy. Do not use.
    public EmployeeScheduleResource() {
        this.solverManager = null;
        this.solutionManager = null;
    }

    @Inject
    public EmployeeScheduleResource(SolverManager<EmployeeSchedule, Long> solverManager,
            SolutionManager<EmployeeSchedule, HardSoftScore> solutionManager) {
        this.solverManager = solverManager;
        this.solutionManager = solutionManager;
    }

    // To try, open http://localhost:8080/schedule
    @GET
    public EmployeeSchedule getSchedule() {
        // Get the solver status before loading the solution
        // to avoid the race condition that the solver terminates between them
        SolverStatus solverStatus = getSolverStatus();
        EmployeeSchedule solution = findById(SINGLETON_SCHEDULE_ID);
        solutionManager.update(solution); // Sets the score
        solution.setSolverStatus(solverStatus);
        return solution;
    }

    public SolverStatus getSolverStatus() {
        return solverManager.getSolverStatus(SINGLETON_SCHEDULE_ID);
    }

    public SolverStatus getSolverStatus(Long jodId) {
        return solverManager.getSolverStatus(jodId);
    }

    @POST
    @Path("solve")
    public void solve() {
        solverManager.solveBuilder()
                .withProblemId(SINGLETON_SCHEDULE_ID)
                .withProblemFinder(this::findById)
                .withBestSolutionConsumer(this::save)
                .run();
    }

    @POST
    @Transactional
    @Path("publish")
    public void publish() {
        if (!getSolverStatus().equals(SolverStatus.NOT_SOLVING)) {
            throw new IllegalStateException("Cannot publish a schedule while solving is in progress.");
        }
        ScheduleState scheduleState = scheduleStateRepository.findById(SINGLETON_SCHEDULE_ID);
        LocalDate newHistoricDate = scheduleState.getFirstDraftDate();
        LocalDate newDraftDate = scheduleState.getFirstDraftDate().plusDays(scheduleState.getPublishLength());

        scheduleState.setLastHistoricDate(newHistoricDate);
        scheduleState.setFirstDraftDate(newDraftDate);

        dataGenerator.generateDraftShifts(scheduleState);
    }

    @POST
    @Path("stopSolving")
    public void stopSolving() {
        solverManager.terminateEarly(SINGLETON_SCHEDULE_ID);
    }

    @Transactional
    protected EmployeeSchedule findById(Long id) {
        if (!SINGLETON_SCHEDULE_ID.equals(id)) {
            throw new IllegalStateException("There is no schedule with id (" + id + ").");
        }
        return new EmployeeSchedule(
                scheduleStateRepository.findById(SINGLETON_SCHEDULE_ID),
                availabilityRepository.listAll(Sort.by("date").and("id")),
                employeeRepository.listAll(Sort.by("name")),
                shiftRepository.listAll(Sort.by("location").and("start").and("id")));
    }

    @Transactional
    protected void save(EmployeeSchedule schedule) {
        for (Shift shift : schedule.getShifts()) {
            // TODO this is awfully naive: optimistic locking causes issues if called by the
            // SolverManager
            Shift attachedShift = shiftRepository.findById(shift.getId());
            attachedShift.setEmployee(shift.getEmployee());
        }
    }

    //////////////////

    @POST
    @Consumes({ MediaType.APPLICATION_JSON })
    @Produces(MediaType.TEXT_PLAIN)
    public Long solve(EmployeeSchedule problem) {
        // String jobId = UUID.randomUUID().toString();
        // Long jobId = uuid.getMostSignificantBits() & Long.MIN_VALUE;
        Random ran = new Random();
        int x = ran.nextInt(100000) + 5;
        Long jobId = Long.valueOf(x);
        jobIdToJob.put(jobId, Job.ofSchedule(problem));
        solverManager.solveBuilder()
                .withProblemId(jobId)
                .withProblemFinder(jobId_ -> jobIdToJob.get(jobId).schedule)
                .withBestSolutionConsumer(solution -> jobIdToJob.put(jobId, Job.ofSchedule(solution)))
                .withExceptionHandler((jobId_, exception) -> {
                    jobIdToJob.put(jobId, Job.ofException(exception));
                    LOGGER.error("Failed solving jobId ({}).", jobId, exception);
                })
                .run();
        return jobId;
    }

    private record Job(EmployeeSchedule schedule, Throwable exception) {

        static Job ofSchedule(EmployeeSchedule schedule) {
            return new Job(schedule, null);
        }

        static Job ofException(Throwable error) {
            return new Job(null, error);
        }
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{jobId}")
    public EmployeeSchedule getSchedule(
            @Parameter(description = "The job ID returned by the POST method.") @PathParam("jobId") Long jobId) {
        EmployeeSchedule schedule = getScheduleAndCheckForExceptions(jobId);
        SolverStatus solverStatus = solverManager.getSolverStatus(jobId);
        schedule.setSolverStatus(solverStatus);
        return schedule;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{jobId}/status")
    public SolverStatus getStatus(
            @Parameter(description = "The job ID returned by the POST method.") @PathParam("jobId") Long jobId) {
        EmployeeSchedule schedule = getScheduleAndCheckForExceptions(jobId);
        SolverStatus solverStatus = solverManager.getSolverStatus(jobId);
        schedule.setSolverStatus(solverStatus);
        return schedule.getSolverStatus();
    }

    private EmployeeSchedule getScheduleAndCheckForExceptions(Long jobId) {
        Job job = jobIdToJob.get(jobId);
        if (job == null) {
            throw new ScheduleSolverException(jobId, Response.Status.NOT_FOUND, "No data found.");
        }
        if (job.exception != null) {
            throw new ScheduleSolverException(jobId, job.exception);
        }
        return job.schedule;
    }

    /////////////////
}
