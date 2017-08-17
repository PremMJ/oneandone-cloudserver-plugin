# oneandone-cloudserver-jenkins
Jenkins plugin for 1&amp;1 Cloud Server

## Table of Contents

* [Description](#description)
* [Getting Started](#getting-started)
* [Usage](#usage)
    * [Cloud Option Reference](#cloud-option-reference)
    * [Server Option Reference](#server-option-reference)
* [Support](#support)

## Description

This plugin allows Jenkins to start slaves on [1&1 Cloud Server API](https://cloudpanel-api.1and1.com/documentation/index_en.html) on demand, and delete them as they get unused.

## Getting Started

Jenkins plugin for 1&amp;1 Cloud Server has the following requirements:

* 1&1 account (API key)

Before you begin, you need to have a 1&1 account.

To enable the API token:

1. Log in to your 1&1 Control Panel and select the relevant package.
2. Click **1&1 Cloud Panel** from the Cloud Server section of the control panel.
3. Select **Users** from the **Management** section of the **Infrastructure** menu.
4. Select the user who needs the API token.
5. In the API section in the lower part of the screen, click **Disabled** next to the API KEY.
6. Click **OK** to activate the API key.

## Usage

After the plugin is installed, navigate to the main "Manage Jenkins" > "Configure System" page, and scroll down near the bottom to the "Cloud" section. There, click the "Add a new cloud" button, and select the "1&amp;1" option. This will display the UI for configuring the 1&amp;1 Cloud Server plugin. 

#### Cloud Option Reference

The following table lists the cloud options and their brief descriptions:

| Name | Required | Description |
| --- | :-: | --- |
| Unique name | yes | The unique name for the cloud |
| API Token | yes | The 1&amp;1 authentication key |
| SSH public key | yes | A valid public SSH Key to be copied into the server during creation |
| SSH private key | yes | A valid private SSH key to permit sshing into the instances that are started |
| Instance cap | yes | The upper limit to the number of 1&amp;1 servers that Jenkins is allowed to launch |
| Timeout in minutes | yes | Time reserved for the operations on this cloud to complete before triggering a timeout, expressed in minutes |

Once the options fields are filled with correct values, use "Test Connection" button to verify that Jenkins can successfully talk to 1&amp;1 Cloud Server API.

After configuring cloud options and verifiying that a connection is successfully established with 1&amp;1 Cloud Server API, a new slave template can be added by clicking "Add" button. Slave templates will be used to create servers and launch them as slaves. 

#### Server Option Reference

The following table lists the server options and their brief descriptions:

| Name | Required | Description |
| --- | :-: | --- |
| Unique name | yes | An unique name for the Slave Template / Server. Note that no Slave Template should have the same name within a Cloud, thus why it's "unique". As the Slave Template name is used in server names, only A-Z, a-z. 0-9 and . can be used in the name. |
| Fixed instance size | yes | The size used for provisioning new servers from this template. |
| Run as user | yes | Jekins will ssh into the server as the user provided in this field. If you specify anything other than "root", make sure that such user exist and that Jenkins will be able to ssh using the SSH private key specified for this Cloud. |
| Jenkins workspace directory path | yes | Absolute path to a directory in which Jenkins will put the workspace directory. Make sure that the user Jenkins runs as has enough permissions to write into that directory. |
| SSH port | yes | The port at which the SSH daemon is listening for the incoming SSH connections. |
| Labels | yes | Labels (AKA tags) are used for grouping multiple slaves into one logical group. Use spaces between each label. For instance 'regression centos7' will assign a node the labels 'regression' and 'centos7'. For example, if you have multiple CentOS 7 slaves and you have jobs that require CentOS 7, then you can configure all your CentOS 7 slaves to have the label 'centos7', then tie the job to the 'centos7' label. This allows your job to run on any of your CentOS 7 slaves but not on anywhere else. |
| Allow jobs with no label restriction | false | If there is non-empty set of labels specified and this is checked, jobs that are not restricted to run on any specific node will trigger creation of a Server based on this Slave Template.</br></br>If there is non-empty set of labels specified and this is not checked, jobs that are not restricted to run on any specific node will not trigger creation of a Server based on this Slave Template.</br></br>If no labels for this Slave Template are specified, then regardless of whether this option is checked or not, jobs that are not restricted to run on any specific node will trigger creation of a Server based on this Slave Template. |
| Number of executors | yes | Determines how many executors can run on the slave. Should be an integer greater than zero. |
| Idle termination time | yes | Determines how long slaves can remain idle before being terminated - that is the server being destroyed.</br></br>Times are expressed in minutes, and a value of 0 indicates that idle slaves should never be terminated.</br></br>As Jenkins doesn't check the idle status of slaves every minute even if you tell it to (it might skip checks for a few minutes), Jenkins might terminate the slave a few minutes late of what you specify in this field. |
| Instance cap | yes | The upper limit to the number of 1&1 servers that Jenkins is allowed to launch for this Slave Template. |
| Init script | no | Initialisation script used for slave setup. |

## Support

You are welcome to contact us with questions or comments using the **Community** section of the [1&1 Cloud Community](https://www.1and1.com/cloud-community). Please report any feature requests or issues using GitHub issue tracker.

* Ask a question or discuss at [1&1 Cloud Community](https://www.1and1.com/cloud-community).
* Report an [issue here](https://github.com/1and1/oneandone-cloudserver-jenkins/issues).

## Contributing

1. Fork the repository ([https://github.com/1and1/oneandone-cloudserver-jenkins/fork](https://github.com/1and1/oneandone-cloudserver-jenkins/fork))
2. Create your feature branch (git checkout -b my-new-feature)
3. Commit your changes (git commit -am 'Add some feature')
4. Push to the branch (git push origin my-new-feature)
5. Create a new Pull Request