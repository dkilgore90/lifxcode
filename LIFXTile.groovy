import groovy.json.JsonSlurper
import groovy.json.JsonOutput

/**
 *
 * Copyright 2018, 2019 Robert Heyes. All Rights Reserved
 *
 *  This software is free for Private Use. You may use and modify the software without distributing it.
 *  You may not grant a sublicense to modify and distribute this software to third parties.
 *  Software is provided without warranty and your use of it is at your own risk.
 *
 */

metadata {
    definition(name: 'LIFX Tile', namespace: 'robheyes', author: 'Robert Alan Heyes', importUrl: 'https://raw.githubusercontent.com/robheyes/lifxcode/master/LIFXTile.groovy') {
        capability 'Light'
        capability 'ColorControl'
        capability 'ColorTemperature'
        capability 'Polling'
        capability 'Initialize'
        capability 'Switch'
        capability 'SwitchLevel'

        attribute "label", "string"
        attribute "group", "string"
        attribute "location", "string"
        attribute "effect", "string"
        attribute "deviceChain", "number"
        
        command "setTiles", [[name: "Tile index*", type: "NUMBER"], [name: "Colors*", type: "JSON_OBJECT"], [name: "Number of tiles", type: "NUMBER"], [name: "Duration", type: "NUMBER"]]
        command "setEffect", [[name: "Effect type*", type: "ENUM", constraints: ["FLAME", "MORPH", "OFF"]], [name: "Colors", type: "JSON_OBJECT"], [name: "Palette Count", type: "NUMBER"], [name: "Speed", type: "NUMBER"]]
        command "tilesSave", [[name: "Matrix name*", type: "STRING"]]
        command "tilesDelete", [[name: "Matrix name*", type: "STRING"]]
        command "tilesLoad", [[name: "Matrix name*", type: "STRING",], [name: "Duration", type: "NUMBER"]]
        command "disable"
        command "getDeviceChain"
        command "deleteChildDevices"
    }

    preferences {
        input "useActivityLogFlag", "bool", title: "Enable activity logging", required: false
        input "useDebugActivityLogFlag", "bool", title: "Enable debug logging", required: false
        input "defaultTransition", "decimal", title: "Level transition time", description: "Set transition time (seconds)", required: true, defaultValue: 0.0
    }
}

@SuppressWarnings("unused")
def installed() {
    initialize()
}

@SuppressWarnings("unused")
def updated() {
    initialize()
}

def initialize() {
    state.transitionTime = defaultTransition
    state.useActivityLog = useActivityLogFlag
    state.useActivityLogDebug = useDebugActivityLogFlag
    unschedule()
    getDeviceChain()
    requestInfo()
    runEvery1Minute poll
}

@SuppressWarnings("unused")
def refresh() {

}

def disable() {
    unschedule()
}

@SuppressWarnings("unused")
def poll() {
    log.debug("polling tiles")
    parent.lifxQuery(device, 'DEVICE.GET_POWER') { List buffer -> sendPacket buffer }
    parent.lifxQuery(device, 'LIGHT.GET_STATE') { List buffer -> sendPacket buffer }
    //If we pass a length > 1, LIFX will send multiple responses, 1 for each tile
    //Workaround to parse TILE.STATE_TILE_STATE for each tile
    log.debug("state.tileCount: ${state.tileCount}")
    //for (int i = 0; i < state.tileCount ?: 1; i++) {
    for (int i = 0; i < 5; i++) {
        parent.lifxQuery(device, 'TILE.GET_TILE_STATE', [tile_index: i, length: 1, x: 0, y: 0, width: 8]) { List buffer -> sendPacket buffer }    
    }
    parent.lifxQuery(device, 'TILE.GET_TILE_EFFECT') { List buffer -> sendPacket buffer }
}

def requestInfo() {
    poll()
}

def getDeviceChain() {
    parent.lifxQuery(device, 'TILE.GET_DEVICE_CHAIN') { List buffer -> sendPacket buffer }
}

def processChainData(data) {
    log.debug("processing chain data: $data")
    state.tileCount = data.total_count
    for (i=0; i<data.total_count; i++) {
        try {
            addChildDevice(
                'robheyes',
                'LIFX Tile Child',
                device.getDeviceNetworkId() + "_tile$i",
                [
                        label   : device.getDisplayName() + " Tile $i",
                        index   : "$i"
                ]
            )
        } catch (com.hubitat.app.exception.UnknownDeviceTypeException e) {
            logWarn "${e.message} - you need to install the appropriate driver"
        } catch (IllegalArgumentException ignored) {
            // Intentionally ignored. Expected if device already present
        }
    }
    def children = getChildDevices()
    for (child in children) {
        def index = child.getDataValue("index") as Integer
        def rotate = determineRotation(data.tile_devices[index].accel_meas_x, data.tile_devices[index].accel_meas_y, data.tile_devices[index].accel_meas_z)
        child.sendEvent(name: "width", value: data.tile_devices[index].width)
        child.sendEvent(name: "height", value: data.tile_devices[index].height)
        child.sendEvent(name: "user_x", value: data.tile_devices[index].user_x)
        child.sendEvent(name: "user_y", value: data.tile_devices[index].user_y)
        child.sendEvent(name: "rotation", value: rotate)
    }
}

def updateTileChild(data) {
    def index = data.tile_index
    def child = getChildDevice(device.getDeviceNetworkId() + "_tile$index")
    child ? child.sendEvent(name: "matrix", value: data.matrixHtml) : logWarn("child device not found for index $index")
    child ? child.sendEvent(name: "lastMatrix", value: JsonOutput.toJson(data.colors)) : null
}

def deleteChildDevices() {
    def children = getChildDevices()
    for (child in children) {
        deleteChildDevice(child.getDeviceNetworkId())
    }
}

def determineRotation(int x, int y, int z) {
    def absX = Math.abs(x)
    def absY = Math.abs(y)
    def absZ = Math.abs(z)
    if (x == -1 && y == -1 && z == -1) {
    // Invalid data, assume right-side up.
        return "rightSideUp"

    } else if (absX > absY && absX > absZ) {
        if (x > 0) {
            return "rotateRight"
        } else {
            return "rotateLeft"
        }

    } else if (absZ > absX && absZ > absY) {
        if (z > 0) {
            return "faceDown"
        } else {
            return "faceUp"
        }

    } else {
        if (y > 0) {
            return "upsideDown"
        } else {
            return "rightSideUp"
        }
    }
}

def on() {
    sendActions parent.deviceOnOff('on', getUseActivityLog(), state.transitionTime ?: 0)
}


def off() {
    sendActions parent.deviceOnOff('off', getUseActivityLog(), state.transitionTime ?: 0)
}

def tilesSave(String name) {
    def children = getChildDevices()
    logInfo("Saving current tile state to name: $name in all child devices")
    for (child in children) {
        child.matrixSave(name)
    }
}

def tilesLoad() {
    def children = getChildDevices()
    logInfo("Loading tile state from name: $name for all child devices")
    for (child in children) {
        child.matrixLoad(name)
    }
}

def tilesDelete() {
    def children = getChildDevices()
    logInfo("Deleting tile state with name: $name from all child devices")
    for (child in children) {
        child.matrixDelete(name)
    }
}

def setTiles(index, String colors, length = 1, duration = 0) {
    def child = getChildDevice(device.getDeviceNetworkId + "_tile$index")
    def childState = child.getState()
    def colorsMap = new JsonSlurper().parseText(childState.lastMatrix)
    colorsMap << new JsonSlurper().parseText(colors)
    def hsbkList = new Map<String, Object>[64]
    for (int i = 0; i < 64; i++) {
        String namedColor = colorsMap[i].color ?: colorsMap[i].colour
        if (namedColor) {
            Map myColor
            myColor = (null == namedColor) ? null : parent.lookupColor(namedColor.replace('_', ' '))
            hsbkList[i] = [
                hue       : parent.scaleUp(myColor.h ?: 0, 360),
                saturation: parent.scaleUp100(myColor.s ?: 0),
                brightness: parent.scaleUp100(myColor.v ?: 50)
            ]
        } else {
            hsbkList[i] = parent.getScaledColorMap(colorsMap[i])
        }
    }
    sendActions parent.deviceSetTileState(index, length, 8, state.transitionTime ?: duration, colorsMap)
}

def childSetTiles(index, Map colors, duration = 0) {
    def hsbkList = new Map<String, Object>[64]
    for (int i = 0; i < 64; i++) {
        String namedColor = colorsList[i].color ?: colorsList[i].colour
        if (namedColor) {
            Map myColor
            myColor = (null == namedColor) ? null : parent.lookupColor(namedColor.replace('_', ' '))
            hsbkList[i] = [
                hue       : parent.scaleUp(myColor.h ?: 0, 360),
                saturation: parent.scaleUp100(myColor.s ?: 0),
                brightness: parent.scaleUp100(myColor.v ?: 50)
            ]
        } else {
            hsbkList[i] = parent.getScaledColorMap(colorsMap[i])
        }
    }
    sendActions parent.deviceSetTileState(index, 1, 8, state.transitionTime ?: duration, colors)
}

def setEffect(String effectType, colors = '[]', palette_count = 16, speed = 30) {
    log.debug("Effect inputs -- type: $effectType, speed: $speed, palette_count: $palette_count, colors: $colors")
    def colorsList = new JsonSlurper().parseText(colors)
    if (colorsList.size() >= 1) {
        palette_count = colorsList.size()
    }
    def hsbkList = new Map<String, Object>[palette_count]
    for (int i = 0; i < palette_count; i++) {
        if (colorsList[i]) {
            String namedColor = colorsList[i].color ?: colorsList[i].colour
            if (namedColor) {
                Map myColor
                myColor = (null == namedColor) ? null : parent.lookupColor(namedColor.replace('_', ' '))
                hsbkList[i] = [
                    hue       : parent.scaleUp(myColor.h ?: 0, 360),
                    saturation: parent.scaleUp100(myColor.s ?: 0),
                    brightness: parent.scaleUp100(myColor.v ?: 50)
                ]
            } else {
                hsbkList[i] = parent.getScaledColorMap(colorsList[i])
            }
        } else {
            hsbkList[i] = [hue: 0, saturation: 0, brightness: 0]
        }
    }
    log.debug("Sending effect command -- type: $effectType, speed: $speed, palette_count: $palette_count, hsbkList: $hsbkList")
    sendActions parent.deviceSetTileEffect(effectType, speed.toInteger(), palette_count.toInteger(), hsbkList)
}

@SuppressWarnings("unused")
def setColor(Map colorMap) {
    log.debug("setColor: $colorMap")
    sendActions parent.deviceSetColor(device, colorMap, getUseActivityLogDebug(), state.transitionTime ?: 0)
}

@SuppressWarnings("unused")
def setHue(hue) {
    log.debug("setHue: $hue")
    sendActions parent.deviceSetHue(device, hue, getUseActivityLog(), state.transitionTime ?: 0)
}

@SuppressWarnings("unused")
def setSaturation(saturation) {
    log.debug("setSat: $saturation")
    sendActions parent.deviceSetSaturation(device, saturation, getUseActivityLog(), state.transitionTime ?: 0)
}

@SuppressWarnings("unused")
def setColorTemperature(temperature) {
    log.debug("setTemp: $temperature")
    sendActions parent.deviceSetColorTemperature(device, temperature, getUseActivityLog(), state.transitionTime ?: 0)
}

def setLevel(level, duration = 0) {
    sendActions parent.deviceSetLevel(device, level as Number, getUseActivityLog(), duration)
}

private void sendActions(Map<String, List> actions) {
    actions.commands?.each { item -> parent.lifxCommand(device, item.cmd, item.payload) { List buffer -> sendPacket buffer, true } }
    actions.events?.each { sendEvent it }
}

def parse(String description) {
    List<Map> events = parent.parseForDevice(device, description, getUseActivityLog())
    def chainEvent = events.find { it.name == 'deviceChain' }
    chainEvent?.data ? processChainData(chainEvent.data) : null
    def tileEvent = events.find { it.name == 'matrixState' }
    tileEvent?.data ? updateTileChild(tileEvent.data) : null
    events.collect { createEvent(it) }
}

private String myIp() {
    device.getDeviceNetworkId()
}

private void sendPacket(List buffer, boolean noResponseExpected = false) {
    log.debug(buffer)
    String stringBytes = hubitat.helper.HexUtils.byteArrayToHexString parent.asByteArray(buffer)
    sendHubCommand(
            new hubitat.device.HubAction(
                    stringBytes,
                    hubitat.device.Protocol.LAN,
                    [
                            type              : hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT,
                            destinationAddress: myIp() + ":56700",
                            encoding          : hubitat.device.HubAction.Encoding.HEX_STRING,
                            ignoreResponse    : noResponseExpected
                    ]
            )
    )
}

def compressMatrixData(Map matrixMap) {
    //passthrough to parent app
    parent.compressMatrixData matrixMap
}

def getUseActivityLog() {
    if (state.useActivityLog == null) {
        state.useActivityLog = true
    }
    return state.useActivityLog
}

def setUseActivityLog(value) {
    log.debug("Setting useActivityLog to ${value ? 'true':'false'}")
    state.useActivityLog = value
}

def getUseActivityLogDebug() {
    if (state.useActivityLogDebug == null) {
        state.useActivityLogDebug = false
    }
    return state.useActivityLogDebug
}

def setUseActivityLogDebug(value) {
    log.debug("Setting useActivityLogDebug to ${value ? 'true':'false'}")
    state.useActivityLogDebug = value
}

void logDebug(msg) {
    if (getUseActivityLogDebug()) {
        log.debug msg
    }
}

void logInfo(msg) {
    if (getUseActivityLog()) {
        log.info msg
    }
}

void logWarn(String msg) {
    if (getUseActivityLog()) {
        log.warn msg
    }
}
