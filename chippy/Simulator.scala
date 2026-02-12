package edu.berkeley.cs.chippy

import chisel3.simulator.ControlAPI
import chisel3.simulator.PeekPokeAPI
import chisel3.simulator.SimulatorAPI

object ChippySim extends ControlAPI with PeekPokeAPI with SimulatorAPI
