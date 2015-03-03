package org.jenkinsci.plugins.environmentdashboard;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.util.FormValidation;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import net.sf.json.JSONObject;

import org.jenkinsci.plugins.environmentdashboard.dao.DashboardDAO;
import org.jenkinsci.plugins.environmentdashboard.entity.Build;
import org.jenkinsci.plugins.environmentdashboard.utils.DBConnection;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

/**
 * Class to create Dashboard view.
 * @author vipin
 * @date 15/10/2014
 */
public class DashboardBuilder extends BuildWrapper {

    private final String nameOfEnv;
    private final String componentName;
    private final String buildNumber;
    private final String buildJob;
    private final String packageName;
    private List<ListItem> data = Collections.emptyList();
    public boolean addColumns = false;

    @DataBoundConstructor
    public DashboardBuilder(String nameOfEnv, String componentName, String buildNumber, String buildJob, String packageName, boolean addColumns, List<ListItem> data) {
        this.nameOfEnv = nameOfEnv;
        this.componentName = componentName;
        this.buildNumber = buildNumber;
        this.buildJob = buildJob;
        this.packageName = packageName;
        if (addColumns){
            this.addColumns = addColumns;
        }else {
            this.addColumns=false;
        }
        if(this.addColumns){
            this.data = data;
        }else{
            this.data = Collections.emptyList();
        }
    }

    public String getNameOfEnv() {
        return nameOfEnv;
    }
    public String getComponentName() {
        return componentName;
    }
    public String getBuildNumber() {
        return buildNumber;
    }
    public String getBuildJob() {
        return buildJob;
    }
    public String getPackageName() {
        return packageName;
    }
    public List<ListItem> getData(){
        return data;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public Environment setUp(AbstractBuild build, Launcher launcher, BuildListener listener) throws IOException, InterruptedException {
        // PreBuild
        final Integer numberOfDays = ( (getDescriptor().getNumberOfDays() == null) ? 30 : getDescriptor().getNumberOfDays() );
        String passedBuildNumber = build.getEnvironment(listener).expand(buildNumber);
        String passedEnvName = build.getEnvironment(listener).expand(nameOfEnv);
        String passedCompName = build.getEnvironment(listener).expand(componentName);
        String passedBuildJob = build.getEnvironment(listener).expand(buildJob);
        String passedPackageName = build.getEnvironment(listener).expand(packageName);
        List<ListItem> passedColumnData = new ArrayList<ListItem>();
        if (addColumns){
            for (ListItem item : data){
                passedColumnData.add(
                        new ListItem(
                            build.getEnvironment(listener).expand(item.columnName),
                            build.getEnvironment(listener).expand(item.contents)
                            )
                        );
            }
        }
        String returnComment = null;

        if (passedPackageName== null){
            passedPackageName = "";
        }

        if (!(passedBuildNumber.matches("^\\s*$") || passedEnvName.matches("^\\s*$") || passedCompName.matches("^\\s*$"))) {
            returnComment = writeToDB(build, listener, passedEnvName, passedCompName, passedBuildNumber, "PRE", passedBuildJob, numberOfDays, passedPackageName, passedColumnData);
            listener.getLogger().println("Pre-Build Update: " + returnComment);
        } else {
            listener.getLogger().println("Environment dashboard not updated: one or more required values were blank");
        }
        // TearDown - This runs post all build steps
        class TearDownImpl extends Environment {
            @Override
            public boolean tearDown(AbstractBuild build, BuildListener listener) throws IOException, InterruptedException {
                String passedBuildNumber = build.getEnvironment(listener).expand(buildNumber);
                String passedEnvName = build.getEnvironment(listener).expand(nameOfEnv);
                String passedCompName = build.getEnvironment(listener).expand(componentName);
                String passedBuildJob = build.getEnvironment(listener).expand(buildJob);
                String passedPackageName = build.getEnvironment(listener).expand(packageName);
                String doDeploy = build.getEnvironment(listener).expand("$UPDATE_ENV_DASH");
                List<ListItem> passedColumnData = Collections.emptyList();
                String returnComment = null;
                
                if (passedPackageName== null){
                    passedPackageName = "";
                }
                if (doDeploy == null || (!doDeploy.equals("true") && !doDeploy.equals("false"))){
                    doDeploy = "true";
                }

                if (doDeploy.equals("true")){
                    if (!(passedBuildNumber.matches("^\\s*$") || passedEnvName.matches("^\\s*$") || passedCompName.matches("^\\s*$"))) {
                        returnComment = writeToDB(build, listener, passedEnvName, passedCompName, passedBuildNumber, "POST", passedBuildJob, numberOfDays, passedPackageName, passedColumnData);
                        listener.getLogger().println("Post-Build Update: " + returnComment);
                    }
                }else{
                    if (!(passedBuildNumber.matches("^\\s*$") || passedEnvName.matches("^\\s*$") || passedCompName.matches("^\\s*$"))) {
                        returnComment = writeToDB(build, listener, passedEnvName, passedCompName, passedBuildNumber, "NODEPLOY", passedBuildJob, numberOfDays, passedPackageName, passedColumnData);
                        listener.getLogger().println("Post-Build Update: " + returnComment);
                    }
                    
                }

                return super.tearDown(build, listener);
            }
        }
        return new TearDownImpl();
    }

    @SuppressWarnings("rawtypes")
    private String writeToDB(AbstractBuild build, BuildListener listener, String envName, String compName, String currentBuildNum, String runTime, String buildJob, Integer numberOfDays, String packageName, List<ListItem> passedColumnData) {
        String returnComment = null;
        if (envName.matches("^\\s*$") || compName.matches("^\\s*$")) {
            returnComment = "WARN: Either Environment name or Component name is empty.";
            return returnComment;
        }

        DashboardDAO dashboardDAO = new DashboardDAO();
        
        try {
            dashboardDAO.createDashboardTable();
        } catch (SQLException e) {
            return "WARN: Could not create table env_dashboard.";
        }
        
        try {
            dashboardDAO.addColumn("packageName VARCHAR(255)");
        } catch (SQLException e) {
            return "WARN: Could not alter table env_dashboard." + e.getErrorCode() + " " + e.getMessage();
        }
        
        //Get DB connection
        Connection conn = DBConnection.getConnection();

        Statement stat = null;

        String columns = "";
        String contents = "";
        for (ListItem item : passedColumnData){
            columns = columns + ", " +  item.columnName;
            contents = contents + "', '" + item.contents;
            try {
                dashboardDAO.addColumn(item.columnName + " VARCHAR");
            } catch (SQLException e) {
                return "WARN: Could not alter table env_dashboard to add column " + item.columnName + ".";
            }
        }
        String indexValueofTable = envName + '=' + compName;
        String currentBuildResult = "UNKNOWN";
        if (build.getResult() == null && runTime.equals("PRE")) {
            currentBuildResult = "RUNNING";
        } else if (build.getResult() == null && runTime.equals("POST")) {
            currentBuildResult = "SUCCESS";
        } else if (runTime.equals("NODEPLOY")){
            currentBuildResult = "NODEPLOY";   
        }else {
            currentBuildResult = build.getResult().toString();
        }
        String currentBuildUrl = build.getUrl();

        String buildJobUrl;
        //Build job is an optional configuration setting
        if (buildJob.isEmpty()) {
            buildJobUrl = "";
        } else {
            buildJobUrl = "job/" + buildJob + "/" + currentBuildNum;
        }
        
        /* Object represents a environment dashboard Jenkins build */
        Build b = new Build(currentBuildNum,currentBuildUrl,currentBuildResult,envName,compName,buildJobUrl,packageName + contents);

        try {
            if (runTime.equals("PRE")) {
                dashboardDAO.addBuild(indexValueofTable, b);
            } else if(runTime.equals("POST")) {
               dashboardDAO.updateBuild(indexValueofTable, b);
            } else if (runTime.equals("NODEPLOY")){
                dashboardDAO.deleteBuild(indexValueofTable, b);
            }
        } catch (SQLException e) {
          return "Error running query!" + e.getMessage().toString();
        }
        

        try {
            dashboardDAO.deleteBuilds(numberOfDays);
        } catch (SQLException e) {
                return "Error running delete query!" + e.getMessage().toString();
        }
        
        return "Updated Dashboard DB";
    }


    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    @Extension
    public static final class DescriptorImpl extends BuildWrapperDescriptor {

        private String numberOfDays = "30";
        private Integer parseNumberOfDays;
        public DescriptorImpl() {
            load();
        }

        @Override
        public String getDisplayName() {
            return "Details for Environment dashboard";
        }

        public FormValidation doCheckNameOfEnv(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set an Environment name.");
            return FormValidation.ok();
        }

        public FormValidation doCheckComponentName(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set a Component name.");
            return FormValidation.ok();
        }

        public FormValidation doCheckBuildNumber(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please set the Build variable e.g: ${BUILD_NUMBER}.");
            return FormValidation.ok();
        }

        public FormValidation doCheckNumberOfDays(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0) {
                return FormValidation.error("Please set the number of days to retain the DB data.");
            } else {
                try {
                    parseNumberOfDays = Integer.parseInt(value);
                } catch(Exception parseEx) {
                    return FormValidation.error("Please provide an integer value.");
                }
            }
            return FormValidation.ok();
        }

        public boolean isApplicable(AbstractProject<?, ?> item) {
            return true;
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            numberOfDays = formData.getString("numberOfDays");
            if (numberOfDays == null || numberOfDays.equals(""))
            {
                numberOfDays = "30";
            }
            save();
            return super.configure(req,formData);
        }

        public Integer getNumberOfDays() {
            return parseNumberOfDays;
        }

    }
}
