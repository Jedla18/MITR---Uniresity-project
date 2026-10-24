"""
Hardware domku: rozhraní, která `Domek` potřebuje, a jejich simulace pro běh na PC.

Ovladače skutečných součástek pro Raspberry mají každý svůj modul. Knihovny jen pro Raspberry
(smbus2, gpiozero a další) se v nich importují až uvnitř funkcí, aby šel balíček
načíst i na PC.
"""
