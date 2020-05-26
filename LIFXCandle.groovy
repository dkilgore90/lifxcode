import groovy.json.JsonSlurper
import groovy.json.JsonOutput

/**
 *
 * Copyright 2020 David Kilgore. All Rights Reserved
 *
 *  This software is free for Private Use. You may use and modify the software without distributing it.
 *  You may not grant a sublicense to modify and distribute this software to third parties.
 *  Software is provided without warranty and your use of it is at your own risk.
 *
 */

metadata {
    definition(name: 'LIFX Candle', namespace: 'robheyes', author: 'Robert Alan Heyes', importUrl: 'https://raw.githubusercontent.com/robheyes/lifxcode/master/LIFXCandle.groovy') {
        capability 'Light'
        capability 'ColorControl'
        capability 'ColorTemperature'
        capability 'Polling'
        capability 'Initialize'
        capability 'Switch'

        attribute "label", "string"
        attribute "group", "string"
        attribute "location", "string"
        attribute "effect", "string"
        
        command "setMatrix", [[name: "Colors*", type: "JSON_OBJECT"], [name: "Duration", type: "NUMBER"]]
        command "setEffect", [[name: "Effect type*", type: "ENUM", constraints: ["FLAME", "MORPH", "OFF"]], [name: "Colors", type: "JSON_OBJECT"], [name: "Palette Count", type: "NUMBER"], [name: "Speed", type: "NUMBER"]]
        command "matrixSave", [[name: "Matrix name*", type: "STRING"]]
        command "matrixDelete", [[name: "Matrix name*", type: "STRING"]]
        command "matrixLoad", [[name: "Matrix name*", type: "STRING",], [name: "Duration", type: "NUMBER"]]
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
    requestInfo()
    runEvery1Minute poll
}

@SuppressWarnings("unused")
def refresh() {

}

@SuppressWarnings("unused")
def poll() {
    parent.lifxQuery(device, 'DEVICE.GET_POWER') { List buffer -> sendPacket buffer }
    parent.lifxQuery(device, 'LIGHT.GET_STATE') { List buffer -> sendPacket buffer }
    parent.lifxCommand(device, 'TILE.GET_TILE_STATE', [tile_index: i, length: 1, x: 0, y: 0, width: 5]) { List buffer -> sendPacket buffer, true }
    parent.lifxQuery(device, 'TILE.GET_TILE_EFFECT') { List buffer -> sendPacket buffer }
}

def requestInfo() {
    poll()
}

def updateLastMatrix(data) {
    List matrix = []
    for (int i = 0; i < 6; i++) {
        List row = []
        for (int j = 0; j < 5; j++) {
            index = (i * 5) + j
            row << data.colors[index]
        }
        matrix << row
    }
    state.lastMatrix = JsonOutput.toJson(matrix)
}

def on() {
    sendActions parent.deviceOnOff('on', getUseActivityLog(), state.transitionTime ?: 0)
}

def off() {
    sendActions parent.deviceOnOff('off', getUseActivityLog(), state.transitionTime ?: 0)
}

def matrixSave(String name) {
    if (name == '') {
        return
    }
    def matrixes = state.namedMatrixes ?: [:]
    def theMatrixMap = [:]
    def theMatrix = new JsonSlurper().parseText(state.lastMatrix)
    logDebug("matrixSave - parsed input: $theMatrix")
    for (int i = 0; i < 6; i++) {
        theMatrix[i].eachWithIndex { v, k -> theMatrixMap[((i * 5) + k)] = v }
    }
    logDebug("matrixSave - input to compression: $theMatrixMap")
    def compressed = parent.compressMatrixData theMatrixMap
    matrixes[name] = compressed
    state.namedMatrixes = matrixes
    state.knownMatrixes = matrixes.keySet().toString()
}

def matrixLoad(String name, duration = 0) {
    if (null == state.namedMatrixes) {
        logWarn 'No saved matrixes'
    }
    def matrixString = state.namedMatrixes[name]
    if (null == matrixString) {
        logWarn "No such matrix $name"
        return
    }

    def theMatrix = parent.getMatrix(matrixString)
    logDebug "Sending $theMatrix"
    sendActions parent.deviceSetTileState(0, 1, 5, state.transitionTime ?: duration, theMatrix)
}

def matrixDelete(String name) {
    state.namedMatrixes?.remove(name)
    updateKnownMatrixes()
}

private void updateKnownMatrixes() {
    state.knownMatrixes = state.namedMatrixes?.keySet().toString()
}

def setMatrix(String colors, duration = 0) {
    def colorsMap = new JsonSlurper().parseText(state.lastMatrix)
    colorsMap << new JsonSlurper().parseText(colors)
    def hsbkList = new Map<String, Object>[30]
    for (int i = 0; i < 6; i++) {
        for (int j = 0; j < 5; j++) {
            int index = (i * 5) + j
            String namedColor = colorsMap[i][j].color ?: colorsMap[i][j].colour
            if (namedColor) {
                Map myColor
                myColor = (null == namedColor) ? null : parent.lookupColor(namedColor.replace('_', ' '))
                hsbkList[index] = [
                    hue       : parent.scaleUp(myColor.h ?: 0, 360),
                    saturation: parent.scaleUp100(myColor.s ?: 0),
                    brightness: parent.scaleUp100(myColor.v ?: 50)
                ]
            } else {
                hsbkList[index] = parent.getScaledColorMap(colorsMap[i][j])
            }
        }
    }
    sendActions parent.deviceSetTileState(0, 1, 5, state.transitionTime ?: duration, colorsMap)
}

def setEffect(String effectType, colors = '[]', palette_count = 16, speed = 30) {
    logDebug("Effect inputs -- type: $effectType, speed: $speed, palette_count: $palette_count, colors: $colors")
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
            hsbkList[i] = [hue: 0, saturation: 0, brightnes: 0]
        }
    }
    logDebug("Sending effect command -- type: $effectType, speed: $speed, palette_count: $palette_count, hsbkList: $hsbkList")
    sendActions parent.deviceSetTileEffect(effectType, speed.toInteger(), palette_count.toInteger(), hsbkList)
}

@SuppressWarnings("unused")
def setColor(Map colorMap) {
    logDebug("setColor: $colorMap")
    sendActions parent.deviceSetColor(device, colorMap, getUseActivityLogDebug(), state.transitionTime ?: 0)
}

@SuppressWarnings("unused")
def setHue(hue) {
    logDebug("setHue: $hue")
    sendActions parent.deviceSetHue(device, hue, getUseActivityLog(), state.transitionTime ?: 0)
}

@SuppressWarnings("unused")
def setSaturation(saturation) {
    logDebug("setSat: $saturation")
    sendActions parent.deviceSetSaturation(device, saturation, getUseActivityLog(), state.transitionTime ?: 0)
}

@SuppressWarnings("unused")
def setColorTemperature(temperature) {
    logDebug("setTemp: $temperature")
    sendActions parent.deviceSetColorTemperature(device, temperature, getUseActivityLog(), state.transitionTime ?: 0)
}

private void sendActions(Map<String, List> actions) {
    actions.commands?.each { item -> parent.lifxCommand(device, item.cmd, item.payload) { List buffer -> sendPacket buffer, true } }
    actions.events?.each { sendEvent it }
}

def parse(String description) {
    List<Map> events = parent.parseForDevice(device, description, getUseActivityLog())
    def matrixEvent = events.find { it.name == 'matrixState' }
    matrixEvent?.data ? updateLastMatrix(matrixEvent.data) : null
    events.collect { createEvent(it) }
}

private String myIp() {
    device.getDeviceNetworkId()
}

private void sendPacket(List buffer, boolean noResponseExpected = false) {
    logDebug(buffer)
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
