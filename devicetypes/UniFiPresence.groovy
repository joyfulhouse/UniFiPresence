/**
*  Copyright 2018 Bryan Li
*
*  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License. You may obtain a copy of the License at:
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
*  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
*  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
*  for the specific language governing permissions and limitations under the License.
*
*  Unifi WiFi Presence Device Type
*
*  Author: bryan.li@gmail.com
*
*  Date: 2018-02-15
*/

metadata {
	definition (name: "UniFi Presence", namespace: "joyfulhouse", author: "Bryan Li") {
		capability "Configuration"
		capability "Presence Sensor"
		capability "Polling"
		capability "Refresh"
	}

	// simulator metadata
	simulator {
    
	}
       
    // Preferences
    preferences {
        section("UniFi Settings") {
            input "ip", "text", title: "IP Address", description: "IP Address in form 192.168.1.226", required: true, displayDuringSetup: true
            input "port", "text", title: "Port", description: "port in form of 8090", required: true, displayDuringSetup: true
            input "username", "text", title: "Username", description: "Login user", required:true, displayDuringSetup: true
            input "password", "password", title: "Password", description: "Login password", required: true, displayDuringSetup: true
        }
        section ("Device Settings") {
            input "mac", "text", title: "MAC Addr", description: "MAC Address in form of 02A1B2C3D4E5", required: true, displayDuringSetup: true	
        }
    }

	// UI tile definitions
	tiles(scale: 2) {
        standardTile("presence", "device.presence", width: 6, height: 2, canChangeBackground: true) {
			state("present", labelIcon:"st.presence.tile.mobile-present", backgroundColor:"#00A0DC")
			state("not present", labelIcon:"st.presence.tile.mobile-not-present", backgroundColor:"#ffffff")
		}
		standardTile("refresh", "device.switch", width: 2, height: 2, inactiveLabel: false, decoration: "flat") {
			state "default", label:'', action:"refresh", icon:"st.secondary.refresh"
		}
        
		main "presence"
		details(["presence","refresh"])
	}
}

def initialize() {
    state.cookies = []
    state.last_seen = now()
    login()
}

def installed() {
	initialize()
    handlePresenceEvent(false)
	runEvery1Minute("refresh")
}

def updated() {
	initialize()
	runEvery1Minute("refresh")
}

// Device Functions
def poll() {
	refresh()
}

def refresh() {
    log.debug "Refreshing presence status for ${device.label}:${settings.mac}"
    getClientByMAC()
}

def login() {
	if (settings.ip != null && settings.port != null && settings.username != null && settings.password !=null) {        
        log.debug("Logging into UniFi Controller API at ${settings.ip}:${settings.port}")
        
        def params = [
            uri: "https://${settings.ip}:${settings.port}",
            path: "/api/login",
            body: [
                username: settings.username,
                password: settings.password
            ]
        ]
        
        try {
            def cookieIndex = 0;
            
            httpPostJson(params) { resp ->
                resp.headers.each {
                    if(it.name == "Set-Cookie") {
                        log.debug "[${cookieIndex}] ${it.name} : ${it.value}"
                        state.cookies.putAt(cookieIndex, it.value.split(";").getAt(0).trim())               
                        cookieIndex++
                    }
                }
                log.debug "response contentType: ${resp.contentType}"
                log.debug state.cookies
            }
        } catch (e) {
            log.debug "Something went wrong: ${e}"
            log.debug "Params: ${params}"
        }
    }
}

def getClientByMAC() {
    if(!state.cookies){
        initialize()
    }
    
    if(!state.cookies.getAt(0)){
     	initialize()   
    }
    
	if (settings.ip != null && settings.port != null && state.cookies.getAt(0) != "" ) {
        
        def params = [
            uri: "https://${settings.ip}:${settings.port}",
            path: "/api/s/default/stat/sta/${settings.mac}",
            headers: ['Cookie': state.cookies.getAt(0)]
        ]
        
        try {
            httpGet(params) { resp ->
                parseStats(resp)
           	}
        } catch (e) {
            log.debug "Something went wrong: ${e}"
            log.debug "Params: ${params}"
            initialize()
            handlePresenceEvent(false)
        }
    } else {
    	login()
    }
}

def parseStats(response) {
    def meta = response.data.meta
    if(meta.rc == "ok"){
        def data = response.data.data
        def timeDiff = now()/1000L - data.last_seen
        
        if(device.currentState("presence")?.value == "present"){
            if(timeDiff > 300){
            	if (data.is_guest == "false") {
            		handlePresenceEvent(false) 
                } else if (timeDiff > 600) {
                	handlePresenceEvent(false)
                }
            } else {
                log.debug "${device.label} is still here"
            }
        } else if(device.currentState("presence")?.value == "not present"){
            if (timeDiff < 300){
            	handlePresenceEvent(true)
            } else {
                log.debug "${device.label} has not returned since ${data.last_seen}"
            }
        }
    } else if(meta.msg == "api.err.UnknownStation"){
    	if(device.currentState("presence")?.value != "not present"){
        	handlePresenceEvent(false)
        }
    } else if( meta.msg == "api.err.LoginRequired") {
    	login()
    }
}

// sets the device status to present/not present
private handlePresenceEvent(present) {
    def linkText = device.label
    def descriptionText
    if ( present )
    	descriptionText = "${linkText} has arrived"
    else
    	descriptionText = "${linkText} has left"
    def eventMap = [
        name: "presence",
        value: present ? "present" : "not present",
        linkText: linkText,
        descriptionText: descriptionText,
        translatable: true
    ]
    
    log.debug descriptionText
    sendEvent(eventMap)
}
