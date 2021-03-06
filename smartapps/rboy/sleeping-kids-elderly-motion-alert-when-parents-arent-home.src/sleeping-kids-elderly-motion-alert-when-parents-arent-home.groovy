/* **DISCLAIMER**
* THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESSED OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE REGENTS OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
* HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
* Without limitation of the foregoing, Contributors/Regents expressly does not warrant that:
* 1. the software will meet your requirements or expectations;
* 2. the software or the software content will be free of bugs, errors, viruses or other defects;
* 3. any results, output, or data provided through or generated by the software will be accurate, up-to-date, complete or reliable;
* 4. the software will be compatible with third party software;
* 5. any errors in the software will be corrected.
* The user assumes all responsibility for selecting the software and for the results obtained from the use of the software. The user shall bear the entire risk as to the quality and the performance of the software.
*/ 

def clientVersion() {
    return "01.02.01"
}

/**
*  Sleeping Kids Motion Alert When Parents Aren't Home
*
* Copyright RBoy Apps, redistribution of any changes or modified code is not allowed without permission
* 2017-5-26 - Change in ST phone, now multiple SMS numbers are separate by a *
* 2016-11-5 - Added support for automatic code update notifications and fixed an issue with sms
* 2016-8-17 - Added workaround for ST contact address book bug and version
* 2015-2-11 - Initial Release
*/

definition(
    name: "Sleeping Kids/Elderly Motion Alert When Parents Aren't Home",
    namespace: "rboy",
    author: "RBoy Apps",
    description: "Send a message when there is motion with sleeping kids while the parents are away",
    category: "Safety & Security",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Meta/intruder_motion-presence.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Meta/intruder_motion-presence@2x.png"
)

preferences {
    section("Sleeping Kids Motion Alert When Parents Aren't Home v${clientVersion()}") {
    }
    section("Monitor these Sleeping Kids Room(s)") {
        input "sensors", "capability.motionSensor", title: "Motion Sensor(s)?", multiple: true
    }
    section("While the following Parent(s) aren't home") {
        input "parents", "capability.presenceSensor", title: "Parent(s)?", multiple: true
    }
    section("Notification Options") {
        input("recipients", "contact", title: "Send notifications to", multiple: true, required: false) {
            paragraph "You can enter multiple phone numbers to send an SMS to by separating them with a '*'. E.g. 5551234567*4447654321"
            input name: "sms", title: "Send SMS notification to (optional):", type: "phone", required: false
            input name: "notify", title: "Send Push Notification", type: "bool", defaultValue: true
        }
    }
    section() {
        label title: "Assign a name for this SmartApp (optional)", required: false
        input name: "disableUpdateNotifications", title: "Don't check for new versions of the app", type: "bool", required: false
    }
}

def installed() {
    log.trace "Install called with settings $settings"
    initialize()
}

def updated() {
    log.trace "Updated called with settings $settings"
    unsubscribe()
    unschedule()
    initialize()
}

def initialize() {
    subscribe(sensors, "motion.active", motionActiveHandler)

    // Check for new versions of the code
    def random = new Random()
    Integer randomHour = random.nextInt(18-10) + 10
    Integer randomDayOfWeek = random.nextInt(7-1) + 1 // 1 to 7
    schedule("0 0 " + randomHour + " ? * " + randomDayOfWeek, checkForCodeUpdate) // Check for code updates once a week at a random day and time between 10am and 6pm
}

def motionActiveHandler(evt) {
    log.trace "Motion detected, $evt.value: $evt, $settings"

    if (parents.every() { it.latestValue("presence") == "not present" }) { // All parents should be away
        // Send notifications
        def message = "${evt.displayName} saw motion while you were out"
        if (location.contactBookEnabled) {
            log.debug "Sending message to $recipients"
            sendNotificationToContacts(message, recipients)
        } else {
            log.debug "SMS: $sms, Push: $notify"
            sms ? sendText(sms, message) : ""
            notify ? sendPush(message) : sendNotificationEvent(message)
        }
    } else {
        def homeParents = parents.findAll { it.latestValue("presence") == "present" }
        log.debug "Motion detected at $evt.displayName, but presence sensor indicates a parents $homeParents are present"
    }
}

private void sendText(number, message) {
    if (number) {
        def phones = number.split("\\*")
        for (phone in phones) {
            sendSms(phone, message)
        }
    }
}

def checkForCodeUpdate(evt) {
    log.trace "Getting latest version data from the RBoy Apps server"
    
    def appName = "Sleeping Kids Motion Alert When Parents Aren't Home"
    def serverUrl = "http://smartthings.rboyapps.com"
    def serverPath = "/CodeVersions.json"
    
    try {
        httpGet([
            uri: serverUrl,
            path: serverPath
        ]) { ret ->
            log.trace "Received response from RBoy Apps Server, headers=${ret.headers.'Content-Type'}, status=$ret.status"
            //ret.headers.each {
            //    log.trace "${it.name} : ${it.value}"
            //}

            if (ret.data) {
                log.trace "Response>" + ret.data
                
                // Check for app version updates
                def appVersion = ret.data?."$appName"
                if (appVersion > clientVersion()) {
                    def msg = "New version of app ${app.label} available: $appVersion, current version: ${clientVersion()}.\nPlease visit $serverUrl to get the latest version."
                    log.info msg
                    if (!disableUpdateNotifications) {
                        sendPush(msg)
                    }
                } else {
                    log.trace "No new app version found, latest version: $appVersion"
                }
                
                // Check device handler version updates
                def caps = [ sensors, parents ]
                caps?.each {
                    def devices = it?.findAll { it.hasAttribute("codeVersion") }
                    for (device in devices) {
                        if (device) {
                            def deviceName = device?.currentValue("dhName")
                            def deviceVersion = ret.data?."$deviceName"
                            if (deviceVersion && (deviceVersion > device?.currentValue("codeVersion"))) {
                                def msg = "New version of device ${device?.displayName} available: $deviceVersion, current version: ${device?.currentValue("codeVersion")}.\nPlease visit $serverUrl to get the latest version."
                                log.info msg
                                if (!disableUpdateNotifications) {
                                    sendPush(msg)
                                }
                            } else {
                                log.trace "No new device version found for $deviceName, latest version: $deviceVersion, current version: ${device?.currentValue("codeVersion")}"
                            }
                        }
                    }
                }
            } else {
                log.error "No response to query"
            }
        }
    } catch (e) {
        log.error "Exception while querying latest app version: $e"
    }
}