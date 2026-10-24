import logging

from domek.casovac import opakuj


def test_opakuj_vraci_true_a_pri_vyjimce_ukonci_proces():
    volani, konec = [], []
    assert opakuj(lambda: volani.append(1))() is True

    def chyba():
        raise RuntimeError("bum")

    assert opakuj(chyba, ukonci=konec.append)() is False
    assert volani == [1] and konec == [1]


def test_opakuj_vyjimku_zaloguje_i_s_vypisem_volani(caplog):
    def chyba():
        raise RuntimeError("bum")

    with caplog.at_level(logging.ERROR):
        opakuj(chyba, ukonci=lambda kod: None)()
    zaznamy = [z for z in caplog.records if z.levelno == logging.ERROR]
    assert len(zaznamy) == 1
    assert zaznamy[0].exc_info[1].args == ("bum",)  # logging.exception přiloží výjimku i výpis
