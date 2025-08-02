package io.jenkins.plugins.emergencystop;

import hudson.Extension;
import hudson.model.Action;
import hudson.model.Executor;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TopLevelItem;
import hudson.model.RootAction;
import hudson.security.Permission;
import java.io.IOException;
import java.util.logging.Logger;
import javax.servlet.ServletException;
import jenkins.model.CauseOfInterruption;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.verb.POST;

@Extension
public class SimpleEmergencyStopAction implements RootAction {

    private static final Logger LOGGER = Logger.getLogger(SimpleEmergencyStopAction.class.getName());

    @Override
    public String getIconFileName() {
        // Only show the button if user has permission to abort builds
        Jenkins jenkins = Jenkins.get();
        if (jenkins != null && jenkins.hasPermission(Job.CANCEL)) {
            // Use a simple, reliable icon or return null for text link
            return "warning.png"; // Standard Jenkins stop icon
        }
        return null;
    }

    @Override
    public String getDisplayName() {
        return "Emergency STOP Pipelines";
    }

    @Override
    public String getUrlName() {
        return "emergency-stop";
    }

    // Handle GET request to show the emergency stop page
    public void doIndex(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        Jenkins jenkins = Jenkins.get();
        
        // Check if user has permission to cancel jobs
        if (jenkins == null || !jenkins.hasPermission(Job.CANCEL)) {
            rsp.sendError(403, "You need Job.CANCEL permission to access this page");
            return;
        }
        
        // Let Jelly template handle the rendering
        req.getView(this, "index.jelly").forward(req, rsp);
    }

    // Called when the emergency stop button is clicked: POST /emergency-stop/stop
    @POST
    public void doStop(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        Jenkins jenkins = Jenkins.get();

        // Check if user has permission to cancel jobs
        if (jenkins == null || !jenkins.hasPermission(Job.CANCEL)) {
            rsp.sendError(403, "You need Job.CANCEL permission to perform this action");
            return;
        }

        int stoppedBuilds = 0;
        int cancelledQueue = 0;

        try {
            LOGGER.info("EMERGENCY STOP initiated by: " + jenkins.getAuthentication().getName());
            
            // Stop all running builds
            for (TopLevelItem item : jenkins.getItems()) {
                if (item instanceof Job) {
                    Job<?, ?> job = (Job<?, ?>) item;
                    
                    // Check all builds of this job (in case there are multiple running)
                    for (Run<?, ?> build : job.getBuilds()) {
                        if (build.isBuilding()) {
                            Executor executor = build.getExecutor();
                            if (executor != null) {
                                LOGGER.info("Emergency stopping build: " + build.getFullDisplayName());
                                executor.interrupt(Result.ABORTED, 
                                new CauseOfInterruption.UserInterruption("EMERGENCY STOP by " + 
                                    jenkins.getAuthentication().getName()));
                                stoppedBuilds++;
                            }
                        }
                    }
                }
            }

            // Cancel all queued items
            Queue queue = jenkins.getQueue();
            Queue.Item[] queuedItems = queue.getItems();
            for (Queue.Item item : queuedItems) {
                LOGGER.info("Emergency cancelling queued item: " + item.getDisplayName());
                queue.cancel(item);
                cancelledQueue++;
            }

            LOGGER.info("EMERGENCY STOP completed. Stopped: " + stoppedBuilds + " builds, Cancelled: " + cancelledQueue + " queued items");

        } catch (Exception e) {
            LOGGER.severe("Error during emergency stop: " + e.getMessage());
            e.printStackTrace();
            rsp.sendError(500, "Error during emergency stop: " + e.getMessage());
            return;
        }

        // Redirect back to main page with success message
        rsp.setContentType("text/html");
        rsp.getWriter().println(
            "<!DOCTYPE html>" +
            "<html><head><title>Emergency Stop Completed</title>" +
            "<meta http-equiv='refresh' content='10;url=" + jenkins.getRootUrl() + "'>" +
            "</head>" +
            "<body style='font-family: Arial, sans-serif; text-align: center; margin-top: 50px; background: #f8f9fa;'>" +
            "<div style='max-width: 600px; margin: 0 auto; padding: 20px;'>" +
            "<h2 style='color: #d9534f; margin-bottom: 30px;'>Emergency Stop Completed</h2>" +
            "<div style='background: #d4edda; border: 1px solid #c3e6cb; color: #155724; padding: 20px; margin: 20px 0; border-radius: 8px;'>" +
            "<h3 style='margin-top: 0;'>✅ Results:</h3>" +
            "<p style='font-size: 16px; margin: 10px 0;'><strong>Stopped:</strong> " + stoppedBuilds + " running builds</p>" +
            "<p style='font-size: 16px; margin: 10px 0;'><strong>Cancelled:</strong> " + cancelledQueue + " queued items</p>" +
            "</div>" +
            "<p style='color: #6c757d; margin-top: 30px;'>Redirecting to Jenkins dashboard in 10 seconds...</p>" +
            "<p><a href='" + jenkins.getRootUrl() + "' style='color: #007bff; text-decoration: none; font-weight: bold; font-size: 16px;'>← Return to Jenkins Dashboard Now</a></p>" +
            "</div>" +
            "</body></html>"
        );
    }
}